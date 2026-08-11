package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

/**
 * hook 定制器（#52，ADR-0007 决策 3）：对配置驱动的 proxy 源做细粒度 hook 定制，无需新增 Provider 类。
 *
 * <p>{@link ProxySourceFactory} 对每个配置条目依次应用全部定制器（Spring 注入
 * {@code List<ProxyHooksCustomizer>}，默认空）。实现类通常用装饰器包装传入的 {@link ProxyHooks}，
 * 只覆盖需要的 hook（如 {@code mapToolName} / {@code handleCallError} / {@code authHeaders}），
 * 其余委托给配置推导的默认实现。</p>
 */
@FunctionalInterface
public interface ProxyHooksCustomizer {

	/** 定制指定配置条目的 hooks，返回新的 hooks（通常包装 {@code base} 只覆盖需要的 hook）。 */
	ProxyHooks customize(ProxySourceConfig config, ProxyHooks base);
}
