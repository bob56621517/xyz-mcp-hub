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
	 * 端点路径，如 {@code /mcp/builtin/utils}。同一 Hub 内必须唯一。
	 */
	String getPath();

	/**
	 * 部署范围（预留标记）。
	 */
	Scope getScope();

	/**
	 * 源类型（目录 API 元数据，ADR-0011 / issue #34）：native / proxy / container / host / composite。
	 *
	 * <p>默认 {@link SourceType#NATIVE}；{@code HostMcp} 覆盖为 {@link SourceType#HOST}，未来的
	 * ProxyMcp / ContainerMcp 各自覆盖。组合源（specs 发布）不实现本接口，由组合源构建器（#33）
	 * 直接以 {@link SourceType#COMPOSITE} 建源。</p>
	 */
	default SourceType getSourceType() {
		return SourceType.NATIVE;
	}

	/**
	 * 该端点暴露的工具列表。可通过 {@code MethodToolCallbackProvider} 从 {@code @Tool} 注解方法构建。
	 *
	 * <p>{@code NativeMcp} 子类必须实现；{@code ProxyMcpProvider} 不实现——其工具由
	 * {@code HubMcpRegistrar} 启动时从上游 {@code listTools} 透传（可选按提供者固定子集）。</p>
	 */
	default List<ToolCallback> getTools() {
		return List.of();
	}

	/**
	 * 端点是否注册。默认 {@code true}；子类在自身所需关键配置（如 API key）缺失时返回
	 * {@code false}，由 {@code HubMcpRegistrar} 跳过注册（见 ADR-0005）。只做配置检查，
	 * 不发起网络请求——proxy 上游连接失败由注册器在 connect 时兜底。
	 */
	default boolean isEnabled() {
		return true;
	}

}
