package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.utils;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * Utils 原生 MCP 端点提供者，暴露 {@code /mcp/builtin/utils}。
 */
@Component
public class UtilsMcpProvider extends NativeMcp {

	private final List<ToolCallback> tools;

	public UtilsMcpProvider(UtilsTools utilsTools) {
		super(Scope.NETWORK);
		this.tools = List
			.of(MethodToolCallbackProvider.builder().toolObjects(utilsTools).build().getToolCallbacks());
	}

	@Override
	public String getName() {
		return "utils";
	}

	@Override
	public String getPath() {
		return "/mcp/builtin/utils";
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

}
