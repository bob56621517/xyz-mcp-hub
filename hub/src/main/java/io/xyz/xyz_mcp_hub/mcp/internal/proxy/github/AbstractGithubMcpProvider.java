package io.xyz.xyz_mcp_hub.mcp.internal.proxy.github;

import java.util.Map;

import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;

/**
 * GitHub 代理提供者基类：持有上游 URL 与 Bearer token 认证、isEnabled 门控等共性逻辑，
 * 子类只声明端点名/路径与可选工具子集（ADR-0007 决策 3）。
 *
 * <p>缺省（空）token 时源未启用（已注册、目录列出 enabled=false、工具为空，见 ADR-0005 二次修订 /
 * #50）；认证 header 在 token 缺失时返回空 Map，避免产出 {@code Bearer null}。</p>
 */
abstract class AbstractGithubMcpProvider extends ProxyMcpProvider {

	private final String upstreamUrl;
	private final String token;

	AbstractGithubMcpProvider(String upstreamUrl, String token) {
		this.upstreamUrl = upstreamUrl;
		this.token = token;
	}

	@Override
	public final String getUpstreamUrl() {
		return upstreamUrl;
	}

	@Override
	public final Map<String, String> getAuthHeaders() {
		if (token == null || token.isBlank()) {
			return Map.of();
		}
		return Map.of("Authorization", "Bearer " + token);
	}

	@Override
	public final boolean isEnabled() {
		return token != null && !token.isBlank();
	}

}
