package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host.playwright;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host.HostMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * Playwright 浏览器自动化 HostMcp 端点提供者（ADR-0009 例外定位：同宿主真实浏览器交互）。
 *
 * <p>在 Hub JVM 内直接驱动无头 chromium（见 {@link io.xyz.xyz_mcp_hub.playwright.WebSessionRegistry}），工具集见
 * {@link PlaywrightTools}。作为 HostMcp 源（{@link HostMcp}，{@code Scope.HOST}）经单端点
 * {@code /xyz-hub/mcp} 注册，连接方用 {@code includes=[playwright]} 暴露浏览器自动化工具集。
 * 无外部凭据，端点始终注册。</p>
 */
@Component
public class PlaywrightMcpProvider extends HostMcp {

	private final List<ToolCallback> tools;

	public PlaywrightMcpProvider(PlaywrightTools playwrightTools) {
		super();
		this.tools = List
			.of(MethodToolCallbackProvider.builder().toolObjects(playwrightTools).build().getToolCallbacks());
	}

	@Override
	public String getName() {
		return "playwright";
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

}
