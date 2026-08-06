package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 博查搜索原生 MCP 端点提供者，暴露 {@code /mcp/server/bocha}。
 *
 * <p>原生实现博查搜索：直接调用博查 Web Search / AI Search HTTP API，工具见 {@link BochaTools}。
 * 缺少 {@code bocha.api-key} 时端点不注册（见 ADR-0005）。</p>
 */
@Component
public class BochaMcpProvider extends NativeMcp {

	private final List<ToolCallback> tools;
	private final String apiKey;

	public BochaMcpProvider(BochaTools bochaTools, @Value("${bocha.api-key:}") String apiKey) {
		super(Scope.NETWORK);
		this.apiKey = apiKey;
		this.tools = List
			.of(MethodToolCallbackProvider.builder().toolObjects(bochaTools).build().getToolCallbacks());
	}

	@Override
	public boolean isEnabled() {
		return apiKey != null && !apiKey.isBlank();
	}

	@Override
	public String getName() {
		return "bocha";
	}

	@Override
	public String getPath() {
		return "/mcp/server/bocha";
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

}
