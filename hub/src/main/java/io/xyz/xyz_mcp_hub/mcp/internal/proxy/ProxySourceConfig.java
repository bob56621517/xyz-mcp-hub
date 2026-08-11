package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;

/**
 * 配置驱动的 proxy 源条目（yaml {@code mcp.proxies} 之一，#52，ADR-0007 修订）。
 *
 * <p>一个条目声明一个透明转发源：{@code name}（源名 / 工具前缀 {@code {source}_}）、
 * {@code upstream-url}（上游 Streamable HTTP 端点）、{@code auth-header}（可选完整 header
 * 行 {@code "Name: Value"}，如 {@code "Authorization: Bearer <token>"}；也接受裸值自动补
 * {@code Authorization} 名，见 {@link ProxyHooks#parseAuthHeader}）、{@code tools-subset}
 * （可选固定工具子集，空 = 全量透传）、{@code enabled}（可选显式门控，覆盖自动推导）。</p>
 *
 * <p>enabled 门控规则（#52，注册/启用分离）：显式 {@code enabled} 优先；否则源启用当且仅当
 * 未配置认证（公开代理，如 context7 / grep.app / wikidata）或认证 header 解析后非空白（需要
 * 凭据的代理，凭据缺失 → 源未启用，目录列出 {@code enabled=false}、工具为空）。</p>
 */
public record ProxySourceConfig(
		String name,
		String upstreamUrl,
		String authHeader,
		List<String> toolsSubset,
		Boolean enabled) {

	public ProxySourceConfig {
		toolsSubset = toolsSubset == null ? List.of() : List.copyOf(toolsSubset);
	}

	/**
	 * enabled 门控解析（#52）：显式 {@code enabled} 优先；否则与 {@link ProxyHooks#parseAuthHeader}
	 * 语义一致——认证 header 未配置（{@code null}，公开代理）→ 启用；配置但空白或解析不出有效
	 * header（如空 header 名 {@code ": Bearer x"}）→ 未启用，避免「enabled 但发不出认证头」的
	 * 静默鉴权失效。
	 */
	public boolean effectiveEnabled() {
		if (enabled != null) {
			return enabled;
		}
		if (authHeader == null) {
			return true;
		}
		if (authHeader.isBlank()) {
			return false;
		}
		return !ProxyHooks.parseAuthHeader(authHeader).isEmpty();
	}
}
