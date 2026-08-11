/**
 * {@code mcp} 模块的对外 API 包。
 *
 * <p>本包暴露 SPI 类型：{@link io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider}、
 * {@link io.xyz.xyz_mcp_hub.mcp.Scope}、目录元数据 {@link io.xyz.xyz_mcp_hub.mcp.SourceType}
 * （issue #34）。旧组合端点 Space 的领域 VO/SPI（ADR-0008）已随旧多端点整体移除（issue #39）；
 * 组合源机制亦已整体移除（#49，见 ADR-0011 修订）。所有实现类位于
 * {@code io.xyz.xyz_mcp_hub.mcp.internal} 嵌套子包中，按 Spring Modulith 约定自动视为
 * 内部实现，不得被跨模块引用。</p>
 */
@org.springframework.modulith.NamedInterface("api")
package io.xyz.xyz_mcp_hub.mcp;
