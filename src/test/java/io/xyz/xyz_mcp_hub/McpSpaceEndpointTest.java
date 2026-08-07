package io.xyz.xyz_mcp_hub;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.UpstreamMcpApplication;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组合端点 Space 集成测试（ADR-0008）：经 {@code /mcp/config/{name}} 端点验证
 * listTools 的组合结果。只测外部行为，不测内部解析。
 *
 * <p>场景：整端点拉入（默认 devops）、include 挑选、exclude 排除、未启用源跳过、
 * 冲突覆盖、默认 path、引用 proxy 端点（内嵌上游）。无外部依赖（playwright 仅列
 * 工具不触发浏览器；github token 强制为空保证未启用）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
@Import(McpSpaceEndpointTest.SpaceTestConfig.class)
class McpSpaceEndpointTest {

	private static ConfigurableApplicationContext upstreamContext;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@DynamicPropertySource
	static void spacesAndUpstream(DynamicPropertyRegistry registry) {
		// 强制 github 未启用，保证 disabled-src 的"未启用源跳过"场景确定性
		registry.add("github.token", () -> "");

		// 内嵌上游，供引用 proxy 端点场景
		upstreamContext = new SpringApplicationBuilder(UpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		registry.add("proxy.space-test.upstream-url",
				() -> "http://localhost:" + upstreamPort + "/mcp/server/upstream");

		// pick：include 精确挑选
		registry.add("mcp.spaces.pick.sources[0].source", () -> "utils");
		registry.add("mcp.spaces.pick.sources[0].include[0]", () -> "currentDateTime");

		// drop：exclude 排除个别工具
		registry.add("mcp.spaces.drop.sources[0].source", () -> "playwright");
		registry.add("mcp.spaces.drop.sources[0].exclude[0]", () -> "browser_file_upload");

		// combo：include+exclude 组合，排除优先
		registry.add("mcp.spaces.combo.sources[0].source", () -> "playwright");
		registry.add("mcp.spaces.combo.sources[0].include[0]", () -> "browser_navigate");
		registry.add("mcp.spaces.combo.sources[0].include[1]", () -> "browser_click");
		registry.add("mcp.spaces.combo.sources[0].exclude[0]", () -> "browser_click");

		// defaultpath：不指定 path → 默认 /mcp/config/{name}
		registry.add("mcp.spaces.defaultpath.sources[0].source", () -> "utils");

		// custompath：显式自定义 path
		registry.add("mcp.spaces.custompath.path", () -> "/mcp/custom/my-space");
		registry.add("mcp.spaces.custompath.sources[0].source", () -> "utils");

		// disabled-src：引用未启用源（github-readonly 无 token）应跳过，仅剩 utils
		registry.add("mcp.spaces.disabled-src.sources[0].source", () -> "github-readonly");
		registry.add("mcp.spaces.disabled-src.sources[1].source", () -> "utils");

		// conflict：重复引用 utils，同名工具去重（后覆盖）
		registry.add("mcp.spaces.conflict.sources[0].source", () -> "utils");
		registry.add("mcp.spaces.conflict.sources[1].source", () -> "utils");

		// proxy-combo：引用 proxy 端点（工具经上游物化）
		registry.add("mcp.spaces.proxy-combo.sources[0].source", () -> "space-test-proxy");
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

	@Test
	void defaultPathUsesConfigNameConvention() {
		client = connect("/mcp/config/defaultpath");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("currentDateTime");
	}

	@Test
	void customPathIsRespected() {
		client = connect("/mcp/custom/my-space");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("currentDateTime");
	}

	@Test
	void includeSelectsOnlyListedTool() {
		client = connect("/mcp/config/pick");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("currentDateTime");
	}

	@Test
	void excludeRemovesToolFromWholeEndpoint() {
		client = connect("/mcp/config/drop");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name)
			.contains("browser_navigate")
			.doesNotContain("browser_file_upload");
	}

	@Test
	void includeThenExcludeExclusionWins() {
		client = connect("/mcp/config/combo");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("browser_navigate");
	}

	@Test
	void disabledSourceIsSkippedGracefully() {
		client = connect("/mcp/config/disabled-src");
		var tools = client.listTools().tools();
		// github-readonly 未启用被跳过，仅剩 utils 工具
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("currentDateTime");
	}

	@Test
	void conflictingSourcesDeduplicateToolNames() {
		client = connect("/mcp/config/conflict");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("currentDateTime");
	}

	@Test
	void proxySourceToolsAreMaterializedAndForward() {
		client = connect("/mcp/config/proxy-combo");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("echo", "fail");
		var result = client.callTool(McpSchema.CallToolRequest.builder("echo")
			.arguments(Map.of("message", "你好"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		// 物化的 proxy 工具为 SyncMcpToolCallback，call 返回 content 的 JSON 序列化
		assertThat(text.text()).contains("echo: 你好");
	}

	/**
	 * 测试专用 ProxyMcpProvider：指向内嵌上游，供 Space 引用 proxy 端点场景。
	 */
	static class SpaceTestProxyMcpProvider extends ProxyMcpProvider {

		private final String upstreamUrl;

		SpaceTestProxyMcpProvider(String upstreamUrl) {
			this.upstreamUrl = upstreamUrl;
		}

		@Override
		public String getName() {
			return "space-test-proxy";
		}

		@Override
		public String getPath() {
			return "/mcp/builtin/space-test-proxy";
		}

		@Override
		public String getUpstreamUrl() {
			return upstreamUrl;
		}

	}

	/**
	 * 仅本测试 context 注册代理端点。
	 */
	@Configuration(proxyBeanMethods = false)
	static class SpaceTestConfig {

		@Bean
		SpaceTestProxyMcpProvider spaceTestProxyMcpProvider(
				@Value("${proxy.space-test.upstream-url}") String upstreamUrl) {
			return new SpaceTestProxyMcpProvider(upstreamUrl);
		}

	}

}
