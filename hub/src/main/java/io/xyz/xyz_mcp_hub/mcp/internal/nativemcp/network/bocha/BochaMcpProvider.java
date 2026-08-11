package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 博查搜索原生 MCP 源提供者（工具经单端点 {@code /xyz-hub/mcp?includes=[bocha]} 暴露）。
 *
 * <p>原生实现博查搜索：直接调用博查 Web Search / AI Search HTTP API，工具见 {@link BochaTools}。
 * 缺少 {@code bocha.api-key} 时源未启用（已注册、目录列出 enabled=false、工具为空，见 ADR-0005 二次修订 / #50）。</p>
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
	public List<ToolCallback> getTools() {
		return tools;
	}

}
