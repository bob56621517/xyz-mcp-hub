package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp;

import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;

/**
 * 原生 MCP 服务的公共基类：在 Hub 的 JVM 中重新实现第三方服务，直接调用第三方 HTTP API。
 *
 * <p>默认实现 {@link #getScope()}，子类仅需提供名称、路径与工具列表。</p>
 */
public abstract class NativeMcp implements McpEndpointProvider {

	private final Scope scope;

	protected NativeMcp(Scope scope) {
		this.scope = scope;
	}

	@Override
	public final Scope getScope() {
		return scope;
	}

}
