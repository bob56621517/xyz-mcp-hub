package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * Playwright 浏览器自动化原生 MCP 端点提供者，暴露 {@code /mcp/builtin/playwright}。
 *
 * <p>在 Hub JVM 内直接驱动无头 chromium（见 {@link PlaywrightSession}），工具集见
 * {@link PlaywrightTools}。无外部凭据，端点始终注册。</p>
 */
@Component
public class PlaywrightMcpProvider extends NativeMcp {

	private final List<ToolCallback> tools;

	public PlaywrightMcpProvider(PlaywrightTools playwrightTools) {
		super(Scope.NETWORK);
		this.tools = List
			.of(MethodToolCallbackProvider.builder().toolObjects(playwrightTools).build().getToolCallbacks());
	}

	@Override
	public String getName() {
		return "playwright";
	}

	@Override
	public String getPath() {
		return "/mcp/builtin/playwright";
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

}
