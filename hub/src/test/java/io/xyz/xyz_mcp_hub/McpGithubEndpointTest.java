package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.GithubUpstreamMcpApplication;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ConfigProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxySourceConfig;
import io.xyz.xyz_mcp_hub.mcp.internal.single.McpSourceRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitHub 代理源集成测试（mock 联通，见 {@code docs/testing/mcp-service-test-guide.md}；#39 迁移：
 * 旧多端点已移除，改经单端点 {@code /xyz-hub/mcp?includes=[github*]} 暴露；#49 github-readonly
 * 组合源已移除；#52 github-full → github 改名、工具前缀 {@code github_*}）：
 * 内嵌 GitHub 风格上游 MCP Server（只读 get_me / search_issues + 写 create_issue + 错误 fail），
 * 经 {@code mcp.proxies} 配置（auth-header 注入 Bearer token）验证 listTools 透传、isError 透传、
 * 搜索/写工具调用、认证注入与 enabled 门控。
 *
 * <p>无外部依赖：内嵌上游模拟，不触网、不需真实 token。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
class McpGithubEndpointTest {

	/** 不可达 proxy 上游（冻结公共 proxy 源，不触网）。 */
	private static final String UNREACHABLE = "http://localhost:1/mcp";

	private static ConfigurableApplicationContext upstreamContext;

	@LocalServerPort
	private int port;

	@Autowired
	private McpSourceRegistry registry;

	private McpSyncClient client;

	@DynamicPropertySource
	static void githubUpstream(DynamicPropertyRegistry registry) {
		upstreamContext = new SpringApplicationBuilder(GithubUpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		String upstreamUrl = "http://localhost:" + upstreamPort + "/mcp/server/upstream";
		// #52 配置驱动：完整 mcp.proxies 列表（app-props 已置空，须显式提供全部条目）。
		// github 指向内嵌上游 + Bearer 认证注入（启用）；三个公共 proxy 源指向不可达（不触真实网络）
		registry.add("mcp.proxies[0].name", () -> "context7");
		registry.add("mcp.proxies[0].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[1].name", () -> "grep-app");
		registry.add("mcp.proxies[1].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[2].name", () -> "wikidata");
		registry.add("mcp.proxies[2].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[3].name", () -> "github");
		registry.add("mcp.proxies[3].upstream-url", () -> upstreamUrl);
		registry.add("mcp.proxies[3].auth-header", () -> "Authorization: Bearer test-token");
	}

	@AfterAll
	static void stopUpstream() {
		if (upstreamContext != null) {
			upstreamContext.close();
		}
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully();
		}
	}

	private McpSyncClient connect(String endpoint) {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint(endpoint)
			.build();
		var c = McpClient.sync(transport).build();
		c.initialize();
		return c;
	}

	private String callText(McpSyncClient c, String toolName, Map<String, Object> arguments) {
		var result = c.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
		assertThat(result.isError()).isFalse();
		assertThat(result.content()).isNotEmpty();
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	/** 配置装配出的 github 源（从源注册表中按名取）。 */
	private ProxyMcpProvider githubProvider() {
		return registry.sources().stream()
			.filter(s -> "github".equals(s.name()))
			.map(McpSourceRegistry.McpSource::provider)
			.filter(ProxyMcpProvider.class::isInstance)
			.map(ProxyMcpProvider.class::cast)
			.findFirst()
			.orElseThrow();
	}

	@Test
	void githubSourceExposesAllUpstreamTools() {
		client = connect("/xyz-hub/mcp?includes=[github*]");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name)
			.containsExactlyInAnyOrder("github_get_me", "github_search_issues",
					"github_create_issue", "github_fail");
	}

	@Test
	void githubSourceCanCallWriteTool() {
		client = connect("/xyz-hub/mcp?includes=[github*]");
		assertThat(callText(client, "github_create_issue", Map.of("title", "bug")))
			.isEqualTo("created issue #123");
	}

	@Test
	void githubSourcePropagatesUpstreamError() {
		client = connect("/xyz-hub/mcp?includes=[github*]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("github_fail").arguments(Map.of()).build());
		assertThat(result.isError()).isTrue();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("上游模拟失败");
	}

	@Test
	void authHeadersInjectedFromConfiguration() {
		assertThat(githubProvider().getAuthHeaders())
			.containsEntry("Authorization", "Bearer test-token");
	}

	@Test
	void disabledWhenAuthHeaderBlank() {
		// 配置门控（#52）：auth-header 留空 → 源未启用（注册/启用分离）；显式 enabled=false 也强制未启用
		ConfigProxyMcpProvider blankAuth = new ConfigProxyMcpProvider(
				new ProxySourceConfig("github", "http://localhost:1/mcp", "", null, null));
		assertThat(blankAuth.isEnabled()).isFalse();
		// 空白 auth-header 不产出空 header 值
		assertThat(blankAuth.getAuthHeaders()).isEmpty();

		ConfigProxyMcpProvider explicitDisabled = new ConfigProxyMcpProvider(
				new ProxySourceConfig("github", "http://localhost:1/mcp", "Authorization: Bearer x", null, false));
		assertThat(explicitDisabled.isEnabled()).isFalse();

		ConfigProxyMcpProvider enabled = new ConfigProxyMcpProvider(
				new ProxySourceConfig("github", "http://localhost:1/mcp", "Authorization: Bearer x", null, null));
		assertThat(enabled.isEnabled()).isTrue();
	}

}
