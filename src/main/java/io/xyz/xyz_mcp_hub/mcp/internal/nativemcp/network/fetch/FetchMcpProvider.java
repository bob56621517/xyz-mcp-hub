package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * Fetch 原生 MCP 端点提供者，暴露 {@code /mcp/builtin/fetch}。
 *
 * <p>在 Hub JVM 内直接 HTTP 抓取网页（快路径，不走浏览器），接入 SSRF 防护，工具见
 * {@link FetchTools}。无外部凭据，端点始终注册（同 {@code playwright}）。</p>
 */
@Component
public class FetchMcpProvider extends NativeMcp {

	private final List<ToolCallback> tools;

	public FetchMcpProvider(FetchTools fetchTools) {
		super(Scope.NETWORK);
		this.tools = List
			.of(MethodToolCallbackProvider.builder().toolObjects(fetchTools).build().getToolCallbacks());
	}

	@Override
	public String getName() {
		return "fetch";
	}

	@Override
	public String getPath() {
		return "/mcp/builtin/fetch";
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}
}
