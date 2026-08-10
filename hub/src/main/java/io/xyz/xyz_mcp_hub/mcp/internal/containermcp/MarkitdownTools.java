package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.SsrGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * markitdown 容器源的静态冒烟工具集（镜像由我们 pin，工具清单与所 pin 镜像版本绑定，ADR-0011 决策 5）。
 *
 * <p>{@code convert_to_markdown} 对齐 markitdown-mcp 的参数与语义：{@code uri} 支持 {@code http(s)://}
 * / {@code file://} / {@code data:} 协议（官方支持集）。SSRF 预检（ADR-0010 决策 2）：仅当 uri 为
 * {@code http(s)://}（网络代抓）时调用 {@link SsrUrlGuard#check} 静态预检；{@code file://} 指向容器自身
 * 文件系统、{@code data:} 为内嵌数据，不触发宿主网络代抓，不做预检。</p>
 *
 * <p>调用路径（#37 首用拉起）：{@code ContainerManager.ensureRunning} 拉起/复用容器 →
 * {@link ContainerMcpClient} 转发 tools/call 到容器内 MCP 端点 → 返回转换结果。容器启动失败 / 上游不可达
 * 时返回友好文本，不拖垮 hub（#37 验收：源降级）。</p>
 */
public class MarkitdownTools {

	private static final Logger log = LoggerFactory.getLogger(MarkitdownTools.class);

	/** 容器侧工具名（markitdown-mcp 的 convert_to_markdown）。 */
	static final String CONTAINER_TOOL = "convert_to_markdown";

	private final ContainerMcp provider;
	private final SsrUrlGuard ssrUrlGuard;

	public MarkitdownTools(ContainerMcp provider, SsrUrlGuard ssrUrlGuard) {
		this.provider = provider;
		this.ssrUrlGuard = ssrUrlGuard;
	}

	@Tool(name = CONTAINER_TOOL, description = """
			将文件或网页（uri）转换为 Markdown。uri 支持 http(s)://（网络抓取）、file://（容器内文件）
			与 data:（内嵌数据）协议；返回转换后的 Markdown 正文。例：转换一个网页
			uri=https://example.com；或内嵌文本 data:text/plain,hello。
			""")
	public String convert_to_markdown(
			@ToolParam(description = "要转换的 URI：http(s):// 网页、file:// 文件或 data: 内嵌数据") String uri) {
		ContainerSpec spec = provider.requireSpec();
		if (isHttpUri(uri)) {
			try {
				ssrUrlGuard.check(uri);
			}
			catch (SsrGuardException e) {
				return "SSRF 防护拦截：" + e.getMessage();
			}
		}
		try {
			// 首用拉起 + 重试 touch 由 ContainerMcpClient.connect 内 ensureRunning 承担（幂等）
			McpSchema.CallToolResult result = provider.client().call(spec, containerRequest(uri));
			if (result.isError()) {
				return "markitdown 转换失败：" + textOf(result);
			}
			return textOf(result);
		}
		catch (RuntimeException e) {
			log.warn("markitdown 容器调用失败（uri={}）：{}", uri, e.getMessage());
			return "markitdown 容器不可用：" + e.getMessage();
		}
	}

	/** 容器侧 tools/call 请求（工具名用容器内原名，不带源前缀）。 */
	private static McpSchema.CallToolRequest containerRequest(String uri) {
		return McpSchema.CallToolRequest.builder(CONTAINER_TOOL)
			.arguments(Map.of("uri", uri))
			.build();
	}

	/** uri 是否为 http(s) 网络代抓（只有这类才走 SSRF 预检）。 */
	private static boolean isHttpUri(String uri) {
		String lower = uri == null ? "" : uri.toLowerCase();
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	/** 提取工具结果的文本内容（多个 TextContent 换行拼接；非文本内容忽略）。 */
	private static String textOf(McpSchema.CallToolResult result) {
		StringBuilder sb = new StringBuilder();
		for (McpSchema.Content content : result.content()) {
			if (content instanceof McpSchema.TextContent text) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(text.text());
			}
		}
		return sb.toString();
	}
}
