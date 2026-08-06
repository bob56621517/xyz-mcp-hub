package io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.wikidata;

import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wikidata MCP 端点提供者，暴露 {@code /mcp/server/wikidata}。
 *
 * <p>透明代理官方 Wikidata MCP Server（维基数据实体查询），上游默认
 * {@value #DEFAULT_UPSTREAM_URL}，可经 {@code proxy.wikidata.upstream-url} 覆盖
 * （测试经 DynamicPropertySource 指向内嵌上游，见 McpPublicProxyEndpointTest）。</p>
 */
@Component
public class WikidataMcpProvider extends ProxyMcpProvider {

	/** Wikidata 官方 Streamable HTTP 端点（交接文档 PARALLEL-HANDOFF.md）。 */
	public static final String DEFAULT_UPSTREAM_URL = "https://wd-mcp.wmcloud.org/mcp";

	private final String upstreamUrl;

	public WikidataMcpProvider(
			@Value("${proxy.wikidata.upstream-url:" + DEFAULT_UPSTREAM_URL + "}") String upstreamUrl) {
		this.upstreamUrl = upstreamUrl;
	}

	@Override
	public String getName() {
		return "wikidata";
	}

	@Override
	public String getPath() {
		return "/mcp/server/wikidata";
	}

	@Override
	public String getUpstreamUrl() {
		return upstreamUrl;
	}

}
