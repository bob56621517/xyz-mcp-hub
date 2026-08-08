package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.net.URI;
import java.util.Base64;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import io.xyz.xyz_mcp_hub.content.ReadabilityExtractor;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchHttpClient.FetchResponse;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.SsrGuardException;
import io.xyz.xyz_mcp_hub.playwright.BrowserSessionHandle;
import io.xyz.xyz_mcp_hub.playwright.WebSessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * fetch 浏览器路径编排（engine=browser/auto 升级时）：主文档 SSRF 过闸 → 锁定 IP 直连抓取
 * HTML → 会话租约（每次请求独立 context，用完即毁，无状态）→ 子资源逐请求过 SSRF 闸 →
 * {@code setContent} 注入浏览器渲染 → 等待 JS 渲染 → Readability 双路径提取 → 可选截图。
 *
 * <p>SSRF 与快路径一致走 {@link SsrUrlGuard}：主文档经 {@link FetchHttpClient} 锁定 IP 直连
 * 抓取（防 DNS rebinding 二次解析），再以 {@code setContent} 注入渲染，浏览器不直接导航原始
 * URL，杜绝「校验公网 → 浏览器解析内网」绕过；渲染期间页面发起的每个子资源请求经
 * {@code route} 拦截逐请求校验，内网 iframe/子资源一律 {@code abort}，不逃逸。</p>
 *
 * <p>主文档 guard 生产为 {@code SsrUrlGuard::resolveAndCheck}，测试可注入放行本地测试服务的
 * 实现（SSRF 拦截本身由 {@code SsrUrlGuardTest} 与真实 guard 用例覆盖）；子资源 guard 始终为
 * 真实 {@link SsrUrlGuard}，保证「页面含内网子资源不逃逸」始终成立。</p>
 */
@Component
public class BrowserFetchService {

	private final FetchHttpClient http;
	private final WebSessionRegistry registry;
	private final ReadabilityExtractor extractor;
	private final HtmlToMarkdown htmlToMarkdown;
	private final PdfTextExtractor pdfTextExtractor;
	private final long waitAfterLoadMs;
	private final FetchUrlGuard mainGuard;
	private final SsrUrlGuard subresourceGuard;

	@Autowired
	public BrowserFetchService(
			FetchHttpClient http,
			WebSessionRegistry registry,
			ReadabilityExtractor extractor,
			HtmlToMarkdown htmlToMarkdown,
			PdfTextExtractor pdfTextExtractor,
			@Value("${fetch.browser.wait-after-load-ms:1000}") long waitAfterLoadMs) {
		this(http, registry, extractor, htmlToMarkdown, pdfTextExtractor,
				new SsrUrlGuard()::resolveAndCheck, new SsrUrlGuard(), waitAfterLoadMs);
	}

	/** 测试/冒烟构造：主文档 guard 可注入放行本地测试服务的实现（SSRF 拦截由真实 guard 的用例覆盖）。 */
	public BrowserFetchService(
			FetchHttpClient http,
			WebSessionRegistry registry,
			ReadabilityExtractor extractor,
			HtmlToMarkdown htmlToMarkdown,
			PdfTextExtractor pdfTextExtractor,
			FetchUrlGuard mainGuard,
			SsrUrlGuard subresourceGuard,
			long waitAfterLoadMs) {
		this.http = http;
		this.registry = registry;
		this.extractor = extractor;
		this.htmlToMarkdown = htmlToMarkdown;
		this.pdfTextExtractor = pdfTextExtractor;
		this.mainGuard = mainGuard;
		this.subresourceGuard = subresourceGuard;
		this.waitAfterLoadMs = waitAfterLoadMs;
	}

	public String fetch(String url, Integer maxLength, Integer startIndex, boolean raw, boolean screenshot) {
		if (url == null || url.isBlank()) {
			return "请提供要抓取的 URL。";
		}
		int max = (maxLength == null || maxLength <= 0) ? FetchService.DEFAULT_MAX_LENGTH : maxLength;
		int start = (startIndex == null || startIndex < 0) ? 0 : startIndex;
		BrowserResult result = fetchBrowser(url.strip(), raw, screenshot);
		String body = FetchService.chunk(result.content(), start, max);
		if (result.screenshotBase64() == null) {
			return body;
		}
		// 截图不参与 start_index/max_length 分块（截断会破坏 base64），完整附于正文后
		return body + "\n\n【页面截图】\ndata:image/png;base64," + result.screenshotBase64();
	}

	/** 主文档经共享走步器 SSRF 校验 + 锁 IP 直连抓取后，进入渲染/提取。 */
	private BrowserResult fetchBrowser(String url, boolean raw, boolean screenshot) {
		FetchService.FetchedPage fetched = FetchService.fetchFollowingRedirects(mainGuard, http, url);
		return render(fetched.uri(), fetched.response(), raw, screenshot);
	}

	/** HTML → 会话租约 + 浏览器渲染；非 HTML 复用快路径文档类型路由。 */
	private BrowserResult render(URI uri, FetchResponse response, boolean raw, boolean screenshot) {
		String body = response.bodyText();
		String mediaType = response.mediaType();
		if (!FetchService.isHtmlPage(mediaType, body)) {
			return new BrowserResult(extractPlain(response, uri, raw), null);
		}
		String sessionId = registry.create();
		try {
			BrowserSessionHandle handle = registry.handle(sessionId);
			Page page = handle.page();
			page.route("**/*", this::guardSubresource);
			// base href 注入使页内相对子资源解析到原始 URL，再经 route 逐请求过闸
			page.setContent(withBaseUrl(body, uri.toString()));
			page.waitForTimeout(waitAfterLoadMs);
			String extracted = raw ? extractor.extractRawHtml(page) : extractor.extractMarkdown(page);
			if (screenshot) {
				byte[] image = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
				return new BrowserResult(extracted, Base64.getEncoder().encodeToString(image));
			}
			return new BrowserResult(extracted, null);
		}
		finally {
			registry.close(sessionId);
		}
	}

	/** 子资源逐请求过 SSRF 闸：内网 iframe/子资源 abort，公网放行（保 JS 渲染能力）。 */
	private void guardSubresource(Route route) {
		try {
			subresourceGuard.resolveAndCheck(route.request().url());
			route.resume();
		}
		catch (SsrGuardException e) {
			route.abort();
		}
	}

	/** 在文档 head 注入 {@code <base href>}，使相对子资源按原始 URL 解析（Java Playwright 无 setContent url 参数）。 */
	private static String withBaseUrl(String html, String baseUrl) {
		String escaped = baseUrl.replace("&", "&amp;").replace("\"", "&quot;");
		String baseTag = "<base href=\"" + escaped + "\">";
		String lower = html.toLowerCase();
		int headEnd = lower.indexOf("</head>");
		if (headEnd >= 0) {
			return html.substring(0, headEnd) + baseTag + html.substring(headEnd);
		}
		int headStart = lower.indexOf("<head");
		if (headStart >= 0) {
			int gt = html.indexOf('>', headStart);
			if (gt >= 0) {
				return html.substring(0, gt + 1) + baseTag + html.substring(gt + 1);
			}
		}
		return "<html><head>" + baseTag + "</head><body>" + html + "</body></html>";
	}

	private String extractPlain(FetchResponse response, URI uri, boolean raw) {
		String mediaType = response.mediaType();
		if (FetchService.isPdf(mediaType) || FetchService.isPdfUrl(uri)) {
			return pdfTextExtractor.extract(response.body());
		}
		if (FetchService.isText(mediaType)) {
			return response.bodyText();
		}
		String type = mediaType == null || mediaType.isEmpty() ? "未声明" : mediaType;
		return "该内容类型暂不支持：" + type + "（当前支持 HTML、PDF 与纯文本）。";
	}

	/** 浏览器路径结果：content 为正文/raw 文本，screenshotBase64 为可选整页截图（不参与分块）。 */
	record BrowserResult(String content, String screenshotBase64) {
	}
}
