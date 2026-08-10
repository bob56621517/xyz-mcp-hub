package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.SsrGuardException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Fetch 端点的工具集合：单工具 {@code fetch}，对齐官方 mcp-server-fetch 的参数与语义，
 * 并扩展 {@code engine}（auto/curl/browser）与 {@code screenshot} 两个分级参数。
 *
 * <p>引擎分级：{@code curl} 走快路径（直接 HTTP，静态页）；{@code browser} 强制浏览器路径
 * （Playwright 渲染 JS 页面）；{@code auto} 快路径优先，检测到 HTML 含脚本（JS 渲染迹象）
 * 时自动升级浏览器路径。</p>
 */
@Component
public class FetchTools {

	private final FetchService fetchService;
	private final BrowserFetchService browserFetchService;

	public FetchTools(FetchService fetchService, BrowserFetchService browserFetchService) {
		this.fetchService = fetchService;
		this.browserFetchService = browserFetchService;
	}

	@Tool(description = """
			抓取指定 URL 的内容并返回。默认将 HTML 转为 Markdown 正文；raw=true 返回未经清洗的原始内容。
			max_length 控制返回内容的最大字符数（默认 5000），start_index 指定从第几个字符开始截取；
			两者一起实现分块读取。engine 选择抓取引擎：auto 快路径优先、检测到脚本时升级浏览器渲染；
			curl 直接 HTTP 抓取（静态页快）；browser 强制用无头浏览器渲染（JS 页面、反爬场景）。
			screenshot=true 时（仅 browser/auto 升级路径有效）额外返回整页截图（base64 data URL）。
			支持 HTML、PDF（文本提取）与纯文本；图片/Office 等暂不支持。
			""")
	public String fetch(
			@ToolParam(description = "要抓取的 URL（http/https）") String url,
			@ToolParam(required = false, description = "返回内容最大字符数，默认 5000") Integer max_length,
			@ToolParam(required = false, description = "起始截取位置，默认 0") Integer start_index,
			@ToolParam(required = false, description = "返回原始内容而非 Markdown，默认 false") Boolean raw,
			@ToolParam(required = false, description = "抓取引擎：auto（默认，快路径优先，检测到脚本升级浏览器）/ curl（直接 HTTP）/ browser（强制浏览器渲染）") String engine,
			@ToolParam(required = false, description = "浏览器路径下额外返回整页截图（base64 data URL），默认 false") Boolean screenshot) {
		try {
			return fetchInternal(url, max_length, start_index, raw, engine, screenshot);
		}
		catch (SsrGuardException e) {
			return "SSRF 防护拦截：" + e.getMessage();
		}
		catch (FetchException e) {
			return e.getMessage();
		}
		catch (RuntimeException e) {
			return "抓取失败：" + e.getMessage();
		}
	}

	private String fetchInternal(String url, Integer maxLength, Integer startIndex, Boolean raw,
			String engine, Boolean screenshot) {
		String mode = engine == null || engine.isBlank() ? "auto" : engine.toLowerCase();
		boolean isRaw = Boolean.TRUE.equals(raw);
		boolean isShot = Boolean.TRUE.equals(screenshot);
		return switch (mode) {
			case "curl" -> fetchService.fetch(url, maxLength, startIndex, raw);
			case "browser" -> browserFetchService.fetch(url, maxLength, startIndex, isRaw, isShot);
			default -> auto(url, maxLength, startIndex, isRaw, isShot);
		};
	}

	/** auto：快路径优先；快路径 HTML 含脚本（JS 渲染迹象）→ 升级浏览器路径。 */
	private String auto(String url, Integer maxLength, Integer startIndex, boolean raw, boolean screenshot) {
		FetchService.FetchResult result = fetchService.fetchWithMeta(url, maxLength, startIndex, raw);
		if (result.hasScript()) {
			return browserFetchService.fetch(url, maxLength, startIndex, raw, screenshot);
		}
		return result.content();
	}
}
