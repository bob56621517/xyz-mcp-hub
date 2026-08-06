package io.xyz.xyz_mcp_hub.mcp;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

/**
 * MCP 端点提供者 —— Hub 的 SPI 扩展点。
 *
 * <p>
 * 每个内置/代理 MCP 服务实现此接口以在 Hub 上注册一个独立端点。{@code HubMcpRegistrar}
 * 启动时自动发现所有实现，为每个提供者创建独立的 MCP Server + 传输层 + 路由，映射到独立的 URL 路径。
 * </p>
 */
public interface McpEndpointProvider {

	/**
	 * MCP Server 名称，作为握手时的 serverInfo.name。
	 */
	String getName();

	/**
	 * 端点路径，如 {@code /mcp/server/utils}。同一 Hub 内必须唯一。
	 */
	String getPath();

	/**
	 * 部署范围（预留标记）。
	 */
	Scope getScope();

	/**
	 * 该端点暴露的工具列表。可通过 {@code MethodToolCallbackProvider} 从 {@code @Tool} 注解方法构建。
	 *
	 * <p>{@code NativeMcp} 子类必须实现；{@code ProxyMcpProvider} 不实现——其工具由
	 * {@code HubMcpRegistrar} 启动时从上游 {@code listTools} 透传（可选按提供者固定子集）。</p>
	 */
	default List<ToolCallback> getTools() {
		return List.of();
	}

}
