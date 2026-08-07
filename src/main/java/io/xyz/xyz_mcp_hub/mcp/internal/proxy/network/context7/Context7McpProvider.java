package io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.context7;

import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * context7 MCP 端点提供者，暴露 {@code /mcp/server/context7}。
 *
 * <p>透明代理官方 context7 MCP Server（库文档查询），上游默认
 * {@value #DEFAULT_UPSTREAM_URL}，可经 {@code proxy.context7.upstream-url} 覆盖
 * （测试经 DynamicPropertySource 指向内嵌上游，见 McpPublicProxyEndpointTest）。</p>
 */
@Component
public class Context7McpProvider extends ProxyMcpProvider {

	/** context7 官方 Streamable HTTP 端点（交接文档 PARALLEL-HANDOFF.md）。 */
	public static final String DEFAULT_UPSTREAM_URL = "https://mcp.context7.com/mcp";

	private final String upstreamUrl;

	public Context7McpProvider(
			@Value("${proxy.context7.upstream-url:" + DEFAULT_UPSTREAM_URL + "}") String upstreamUrl) {
		this.upstreamUrl = upstreamUrl;
	}

	@Override
	public String getName() {
		return "context7";
	}

	@Override
	public String getPath() {
		return "/mcp/server/context7";
	}

	@Override
	public String getUpstreamUrl() {
		return upstreamUrl;
	}

}
