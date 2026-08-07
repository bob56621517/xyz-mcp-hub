package io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.grepapp;

import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * grep.app MCP 端点提供者，暴露 {@code /mcp/builtin/grep-app}。
 *
 * <p>透明代理官方 grep.app MCP Server（GitHub 开源代码检索），上游默认
 * {@value #DEFAULT_UPSTREAM_URL}，可经 {@code proxy.grep-app.upstream-url} 覆盖
 * （测试经 DynamicPropertySource 指向内嵌上游，见 McpPublicProxyEndpointTest）。</p>
 */
@Component
public class GrepAppMcpProvider extends ProxyMcpProvider {

	/**
	 * grep.app 官方 Streamable HTTP 端点（交接文档 PARALLEL-HANDOFF.md）。
	 *
	 * <p>注意尾斜杠：grep.app 真实 MCP 端点位于根路径，{@code /mcp} 返回
	 * {@code Invalid MCP endpoint}；{@link ProxyMcpProvider#connect()} 把空路径默认拼为
	 * {@code /mcp}，故此处带尾斜杠使 {@code path="/"} 指向根端点。</p>
	 */
	public static final String DEFAULT_UPSTREAM_URL = "https://mcp.grep.app/";

	private final String upstreamUrl;

	public GrepAppMcpProvider(
			@Value("${proxy.grep-app.upstream-url:" + DEFAULT_UPSTREAM_URL + "}") String upstreamUrl) {
		this.upstreamUrl = upstreamUrl;
	}

	@Override
	public String getName() {
		return "grep-app";
	}

	@Override
	public String getPath() {
		return "/mcp/builtin/grep-app";
	}

	@Override
	public String getUpstreamUrl() {
		return upstreamUrl;
	}

}
