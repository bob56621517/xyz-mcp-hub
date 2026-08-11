package io.xyz.xyz_mcp_hub.mcp;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

/**
 * MCP 源提供者 —— Hub 的 SPI 扩展点（ADR-0011 单端点收敛）。
 *
 * <p>
 * 每个 MCP 源实现此接口以注册进源注册表（{@code McpSourceRegistry}），经单端点
 * {@code /xyz-hub/mcp} 暴露。源名（{@link #getName()}）是 URL {@code includes}/{@code excludes}
 * 的最小引用单元；工具名统一加 {@code {source}_} 前缀保证跨源全局唯一。旧多端点（每个提供者一个
 * 独立 URL 路径）已整体移除（issue #39），不再有端点路径概念。
 * </p>
 */
public interface McpEndpointProvider {

	/**
	 * MCP 源名称，作为握手时的 serverInfo.name 与目录中的源名。
	 */
	String getName();

	/**
	 * 部署范围（host/network，目录 API 元数据）。
	 */
	Scope getScope();

	/**
	 * 源类型（目录 API 元数据，ADR-0011 / issue #34，#50 收敛）：native / proxy / container。
	 *
	 * <p>默认 {@link SourceType#NATIVE}；{@code HostMcp} 不再覆盖（host 并入 native，靠
	 * {@code scope} 表达部署，#50），ProxyMcp / ContainerMcp 各自覆盖。组合源已整体移除（#49），
	 * 不再有 composite 类型。</p>
	 */
	default SourceType getSourceType() {
		return SourceType.NATIVE;
	}

	/**
	 * 该源暴露的工具列表。可通过 {@code MethodToolCallbackProvider} 从 {@code @Tool} 注解方法构建。
	 *
	 * <p>{@code NativeMcp} 子类必须实现；{@code ProxyMcpProvider} 不实现——其工具由
	 * {@code McpSourceRegistry} 启动时从上游 {@code listTools} 发现（可选按提供者固定子集）。</p>
	 */
	default List<ToolCallback> getTools() {
		return List.of();
	}

	/**
	 * 源是否启用（注册/启用分离，#50，见 ADR-0005 二次修订）。默认 {@code true}；子类在自身
	 * 所需关键配置（如 API key）缺失时返回 {@code false}。源仍**已注册**（目录列出、
	 * {@code enabled=false}、工具为空），只是**未启用**——不再"缺配置即整个源从目录消失"。
	 * 只做配置检查、不发起网络请求——proxy 上游连接失败由注册表在启动发现时兜底（源降级）。
	 */
	default boolean isEnabled() {
		return true;
	}

}
