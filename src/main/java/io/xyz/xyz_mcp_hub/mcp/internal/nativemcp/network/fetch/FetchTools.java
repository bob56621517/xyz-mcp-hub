package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.SsrGuardException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Fetch 端点的工具集合：单工具 {@code fetch}，对齐官方 mcp-server-fetch 的参数与语义。
 */
@Component
public class FetchTools {

	private final FetchService fetchService;

	public FetchTools(FetchService fetchService) {
		this.fetchService = fetchService;
	}

	@Tool(description = """
		抓取指定 URL 的内容并返回。默认将 HTML 转为 Markdown 正文；raw=true 返回未经清洗的原始内容。
		max_length 控制返回内容的最大字符数（默认 5000），start_index 指定从第几个字符开始截取；
		两者一起实现分块读取。支持 HTML、PDF（文本提取）与纯文本；图片/Office 等暂不支持。
		""")
	public String fetch(
			@ToolParam(description = "要抓取的 URL（http/https）") String url,
			@ToolParam(required = false, description = "返回内容最大字符数，默认 5000") Integer max_length,
			@ToolParam(required = false, description = "起始截取位置，默认 0") Integer start_index,
			@ToolParam(required = false, description = "返回原始内容而非 Markdown，默认 false") Boolean raw) {
		try {
			return fetchService.fetch(url, max_length, start_index, raw);
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
}
