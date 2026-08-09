/**
 * {@code mcp} 模块的对外 API 包。
 *
 * <p>本包暴露 SPI 类型：{@link io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider}、
 * {@link io.xyz.xyz_mcp_hub.mcp.Scope}，以及组合端点 Space 的领域 VO/SPI
 * （{@link io.xyz.xyz_mcp_hub.mcp.SpaceDefinition} / {@link io.xyz.xyz_mcp_hub.mcp.SpaceSource}
 * / {@link io.xyz.xyz_mcp_hub.mcp.SpaceDefinitionSource}）。所有实现类位于
 * {@code io.xyz.xyz_mcp_hub.mcp.internal} 嵌套子包中，按 Spring Modulith 约定自动视为
 * 内部实现，不得被跨模块引用。</p>
 */
@org.springframework.modulith.NamedInterface("api")
package io.xyz.xyz_mcp_hub.mcp;
