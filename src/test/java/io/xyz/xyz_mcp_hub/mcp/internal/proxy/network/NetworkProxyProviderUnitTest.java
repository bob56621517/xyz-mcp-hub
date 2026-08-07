package io.xyz.xyz_mcp_hub.mcp.internal.proxy.network;

import io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.context7.Context7McpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.grepapp.GrepAppMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.wikidata.WikidataMcpProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公共 Proxy 提供者单元测试：守护默认上游 URL 与端点元数据（PARALLEL-HANDOFF.md）。
 * 不加载 Spring 上下文，构造注入任意 URL 验证 getter 行为。
 *
 * <p>无外部依赖：纯内存单元测试，不发起网络请求。</p>
 */
class NetworkProxyProviderUnitTest {

	@Test
	void defaultUpstreamUrlsMatchHandoff() {
		assertThat(Context7McpProvider.DEFAULT_UPSTREAM_URL).isEqualTo("https://mcp.context7.com/mcp");
		// grep.app 真实端点位于根路径，须带尾斜杠（/mcp 返回 Invalid MCP endpoint）
		assertThat(GrepAppMcpProvider.DEFAULT_UPSTREAM_URL).isEqualTo("https://mcp.grep.app/");
		assertThat(WikidataMcpProvider.DEFAULT_UPSTREAM_URL).isEqualTo("https://wd-mcp.wmcloud.org/mcp");
	}

	@Test
	void metadataMatchesPublicContract() {
		var context7 = new Context7McpProvider("http://localhost:1/mcp");
		assertThat(context7.getName()).isEqualTo("context7");
		assertThat(context7.getPath()).isEqualTo("/mcp/server/context7");
		assertThat(context7.getUpstreamUrl()).isEqualTo("http://localhost:1/mcp");

		var grepApp = new GrepAppMcpProvider("http://localhost:1/mcp");
		assertThat(grepApp.getName()).isEqualTo("grep-app");
		assertThat(grepApp.getPath()).isEqualTo("/mcp/server/grep-app");
		assertThat(grepApp.getUpstreamUrl()).isEqualTo("http://localhost:1/mcp");

		var wikidata = new WikidataMcpProvider("http://localhost:1/mcp");
		assertThat(wikidata.getName()).isEqualTo("wikidata");
		assertThat(wikidata.getPath()).isEqualTo("/mcp/server/wikidata");
		assertThat(wikidata.getUpstreamUrl()).isEqualTo("http://localhost:1/mcp");
	}

}
