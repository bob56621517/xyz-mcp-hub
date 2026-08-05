package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;

/**
 * 代理 MCP 服务基类 —— 预留。
 *
 * <p>代理公有云 MCP 的端点提供者应继承此类，作为 MCP Client 连接外部官方 MCP Server 并透明转发
 * （转发路径统一经过 {@link ProxyInterceptor} 拦截器链）。本期无具体代理目标，本类仅为类型层级预留，
 * 不注册任何端点；具体代理实现待接入具体公有云 MCP 服务时落地。</p>
 */
public abstract class ProxyMcpProvider implements McpEndpointProvider {

	@Override
	public final Scope getScope() {
		return Scope.NETWORK;
	}

}
