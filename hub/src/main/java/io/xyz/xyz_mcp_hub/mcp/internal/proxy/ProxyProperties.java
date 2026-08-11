package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mcp.proxies} 配置模型（#52，ADR-0007 决策 2）：配置驱动的 proxy 源列表。
 *
 * <p>前缀 {@code mcp}——Spring AI 的 MCP 属性前缀为 {@code spring.ai.mcp.*}（client/server），
 * 无冲突；由 {@code @ConfigurationPropertiesScan} 自动注册。{@link ProxySourceFactory} 据此
 * 建源，消灭逐个 Provider 类。</p>
 */
@ConfigurationProperties(prefix = "mcp")
public record ProxyProperties(List<ProxySourceConfig> proxies) {

	public ProxyProperties {
		proxies = proxies == null ? List.of() : List.copyOf(proxies);
	}
}
