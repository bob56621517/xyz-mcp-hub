package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchHttpClient.FetchResponse;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.ResolvedTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * fetch 快路径的抓取编排：SSRF 校验 → 锁定 IP 直连 → 逐跳重定向 → 文档类型路由 →
 * HTML→markdown（或 raw）→ start_index/max_length 分块。
 *
 * <p>对齐官方 mcp-server-fetch 语义：url 必填；max_length 默认 5000、start_index 默认 0、
 * raw 默认 false。SSRF 由 {@link FetchUrlGuard} 统一把关（生产为 {@link SsrUrlGuard}），
 * 每跳重定向重新校验。</p>
 */
@Component
public class FetchService {

	/** max_length 未传或非法时的默认值（对齐官方）。 */
	static final int DEFAULT_MAX_LENGTH = 5000;
	/** 重定向逐跳上限，防无限跳。 */
	static final int MAX_REDIRECTS = 5;

	private static final String PREFIX_OMITTED = "(Content before the start_index is omitted)";
	private static final String SUFFIX_OMITTED = "(Content after the end_index is omitted)";

	private final FetchUrlGuard guard;
	private final FetchHttpClient http;
	private final HtmlToMarkdown htmlToMarkdown;
	private final PdfTextExtractor pdfTextExtractor;

	@Autowired
	public FetchService(FetchHttpClient http, HtmlToMarkdown htmlToMarkdown, PdfTextExtractor pdfTextExtractor) {
		this(new SsrUrlGuard()::resolveAndCheck, http, htmlToMarkdown, pdfTextExtractor);
	}

	/** 测试构造：checker 可注入放行本地测试服务的实现（SSRF 拦截由 {@code SsrUrlGuardTest} 覆盖）。 */
	FetchService(FetchUrlGuard guard, FetchHttpClient http, HtmlToMarkdown htmlToMarkdown,
			PdfTextExtractor pdfTextExtractor) {
		this.guard = guard;
		this.http = http;
		this.htmlToMarkdown = htmlToMarkdown;
		this.pdfTextExtractor = pdfTextExtractor;
	}

	public String fetch(String url, Integer maxLength, Integer startIndex, Boolean raw) {
		return fetchWithMeta(url, maxLength, startIndex, raw).content();
	}

	/**
	 * 快路径抓取并返回结果 + 元信息：{@code hasScript} 供 auto 引擎判定 HTML 是否含脚本
	 * （JS 渲染迹象，需要升级浏览器路径）。
	 */
	public FetchResult fetchWithMeta(String url, Integer maxLength, Integer startIndex, Boolean raw) {
		if (url == null || url.isBlank()) {
			return new FetchResult("请提供要抓取的 URL。", false);
		}
		int max = (maxLength == null || maxLength <= 0) ? DEFAULT_MAX_LENGTH : maxLength;
		int start = (startIndex == null || startIndex < 0) ? 0 : startIndex;
		boolean isRaw = Boolean.TRUE.equals(raw);

		Page page = fetchPage(url.strip());
		String content = extractContent(page, isRaw);
		return new FetchResult(chunk(content, start, max), hasScript(page));
	}

	/** HTML 且前部含 {@code <script>} → 判定为 JS 渲染迹象（auto 引擎据此升级浏览器路径）。 */
	private static boolean hasScript(Page page) {
		if (!isHtmlPage(page.response().mediaType(), page.body())) {
			return false;
		}
		String body = page.body();
		String head = body.length() > 8192 ? body.substring(0, 8192) : body;
		return head.toLowerCase(Locale.ROOT).contains("<script");
	}

	/** 快路径结果：content 为分块后文本，hasScript 供 auto 引擎判定。 */
	public record FetchResult(String content, boolean hasScript) {
	}

	/** 逐跳抓取：每跳先 SSRF 校验并锁定，3xx 手动取 Location 重新校验（防 302→内网）。 */
	private Page fetchPage(String url) {
		FetchedPage fetched = fetchFollowingRedirects(guard, http, url);
		return new Page(fetched.uri(), fetched.response());
	}

	/**
	 * 快路径与浏览器路径共享的逐跳抓取走步器：每跳 SSRF 校验并锁定 IP 直连，3xx 手动取
	 * Location 重新校验（防 302→内网）。锁使用本次迭代的局部引用解锁，避免重定向重赋
	 * {@code target} 后遗留已锁地址（DNS 一直解析到旧 IP）。
	 */
	static FetchedPage fetchFollowingRedirects(FetchUrlGuard guard, FetchHttpClient http, String url) {
		String current = url;
		ResolvedTarget target = guard.resolveAndCheck(current);
		for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
			ResolvedTarget locked = target;
			http.lock(locked.host(), locked.firstAddress());
			try {
				FetchResponse response = http.execute(locked.uri());
				if (response.isRedirect()) {
					String location = response.location();
					if (location == null || location.isBlank()) {
						throw new FetchException("重定向响应缺少 Location 头：" + current);
					}
					current = locked.uri().resolve(location).toString();
					target = guard.resolveAndCheck(current);
					continue;
				}
				if (response.status() >= 400) {
					throw new FetchException("抓取失败（HTTP " + response.status() + "）：" + locked.uri());
				}
				return new FetchedPage(locked.uri(), response);
			}
			catch (IOException e) {
				throw new FetchException("抓取失败（" + locked.uri() + "）：" + e.getMessage(), e);
			}
			finally {
				http.unlock(locked.host(), locked.firstAddress());
			}
		}
		throw new FetchException("重定向次数超过上限（" + MAX_REDIRECTS + " 跳），已中止。");
	}

	/** 一次成功抓取的快照：最终 URI（重定向后）+ 响应。 */
	record FetchedPage(URI uri, FetchResponse response) {
	}

	/** 文档类型路由：HTML→markdown（或 raw 原文）、PDF→文本、纯文本直出、其余留桩。 */
	private String extractContent(Page page, boolean raw) {
		String mediaType = page.response().mediaType();
		if (isHtmlPage(mediaType, page.body())) {
			String body = page.body();
			return raw ? body : htmlToMarkdown.convert(body);
		}
		if (isPdf(mediaType) || isPdfUrl(page.uri())) {
			return pdfTextExtractor.extract(page.response().body());
		}
		if (isText(mediaType)) {
			return page.body();
		}
		String type = mediaType == null || mediaType.isEmpty() ? "未声明" : mediaType;
		return "该内容类型暂不支持：" + type + "（当前支持 HTML、PDF 与纯文本）。";
	}

	static boolean isHtml(String mediaType) {
		return mediaType != null && mediaType.contains("html");
	}

	/** mediaType 声明或 body 形态判定是否为 HTML 文档（快路径与浏览器路径共用）。 */
	static boolean isHtmlPage(String mediaType, String body) {
		return isHtml(mediaType) || (mediaType == null && looksLikeHtml(body));
	}

	static boolean isPdf(String mediaType) {
		return mediaType != null && mediaType.contains("pdf");
	}

	static boolean isText(String mediaType) {
		if (mediaType == null) {
			return false;
		}
		return mediaType.startsWith("text/") || mediaType.equals("application/json")
			|| mediaType.contains("xml") || mediaType.contains("javascript");
	}

	static boolean isPdfUrl(URI uri) {
		String path = uri.getPath();
		return path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
	}

	static boolean looksLikeHtml(String body) {
		String head = body.length() > 1024 ? body.substring(0, 1024) : body;
		String lower = head.toLowerCase(Locale.ROOT);
		return lower.contains("<!doctype html") || lower.contains("<html");
	}

	/** start_index/max_length 分块，附前后省略提示（对齐官方）。 */
	static String chunk(String content, int start, int max) {
		int len = content.length();
		int from = (int) Math.min(start, (long) len);
		int to = (int) Math.min((long) start + max, (long) len);
		StringBuilder sb = new StringBuilder();
		if (from > 0) {
			sb.append(PREFIX_OMITTED).append('\n');
		}
		sb.append(content, from, to);
		if (to < len) {
			sb.append('\n').append(SUFFIX_OMITTED);
		}
		return sb.toString();
	}

	private record Page(URI uri, FetchResponse response) {

		String body() {
			return response.bodyText();
		}
	}
}
