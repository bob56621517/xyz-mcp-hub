package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import org.springframework.stereotype.Component;

/**
 * 通用转发器工厂（#52，ADR-0007 决策 2）：从 {@code mcp.proxies} 配置建源，消灭逐个 Provider 类。
 *
 * <p>对每个配置条目构建一个 {@link ConfigProxyMcpProvider}（内部走启动时 {@code listTools} 发现
 * + {@code callTool} 透传，见 {@link ProxyMcpProvider}），经 {@link #sources()} 提供给单端点注册器
 * （{@code McpSingleEndpointRegistrar}）并入源注册表。可选的 {@link ProxyHooksCustomizer} 按序应用，
 * 提供特殊代理的 hook 扩展（不新增 Provider 类）。</p>
 *
 * <p>注：Spring 7 不把 {@code List<McpEndpointProvider>} 形式的 bean 扁平化注入到
 * {@code List<McpEndpointProvider>} 注入点，故这里以 {@code @Component} 暴露 {@link #sources()}，
 * 由注册器显式合并（#52）。</p>
 *
 * <p>新增一个代理服务 = 在 {@code mcp.proxies} 配置加一行 + 认证字段，无需写代码（ADR-0007
 * 后果）；机制层测试用 {@code TestProxyMcpProvider} 覆盖通用转发器。</p>
 */
@Component
public class ProxySourceFactory {

	private final ProxyProperties properties;
	private final List<ProxyHooksCustomizer> customizers;

	public ProxySourceFactory(ProxyProperties properties, List<ProxyHooksCustomizer> customizers) {
		this.properties = properties;
		this.customizers = customizers == null ? List.of() : customizers;
	}

	/** 配置驱动的全部 proxy 源（每个 {@code mcp.proxies} 条目一个）。 */
	public List<McpEndpointProvider> sources() {
		return properties.proxies().stream()
			.map(config -> (McpEndpointProvider) buildProvider(config))
			.toList();
	}

	/** 按配置条目构建一个 proxy 源（应用全部定制器）。 */
	public ConfigProxyMcpProvider buildProvider(ProxySourceConfig config) {
		ProxyHooks hooks = ProxyHooks.from(config);
		for (ProxyHooksCustomizer customizer : customizers) {
			hooks = customizer.customize(config, hooks);
		}
		return new ConfigProxyMcpProvider(config, hooks);
	}
}
