package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.UpstreamMcpApplication;
import io.xyz.xyz_mcp_hub.mcp.internal.HubMcpRegistrar;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proxy 转发集成测试：内嵌上游 MCP Server（已知工具 echo / fail），经 Hub 的代理端点
 * {@code /mcp/server/test-proxy} 验证 listTools 透传、callTool 透明转发（含 isError）与生命周期释放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
@Import(McpProxyEndpointTest.ProxyEndpointTestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpProxyEndpointTest {

	private static ConfigurableApplicationContext upstreamContext;

	@LocalServerPort
	private int port;

	@Autowired
	private HubMcpRegistrar registrar;

	@Autowired
	private TestProxyMcpProvider testProxyMcpProvider;

	private McpSyncClient client;

	@DynamicPropertySource
	static void registerUpstream(DynamicPropertyRegistry registry) {
		upstreamContext = new SpringApplicationBuilder(UpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		registry.add("proxy.test.upstream-url",
				() -> "http://localhost:" + upstreamPort + "/mcp/server/upstream");
		registry.add("proxy.test.auth-token", () -> "test-secret");
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
	@Order(1)
	void listToolsPassthroughFromUpstream() {
		client = connect("/mcp/server/test-proxy");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("echo", "fail");
		var echo = tools.stream().filter(tool -> tool.name().equals("echo")).findFirst().orElseThrow();
		assertThat(echo.description()).isEqualTo("回显消息");
		assertThat(echo.inputSchema()).isNotNull();
	}

	@Test
	@Order(2)
	void callToolForwardsToUpstream() {
		client = connect("/mcp/server/test-proxy");
		var result = client.callTool(McpSchema.CallToolRequest.builder("echo")
			.arguments(Map.of("message", "你好"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("echo: 你好");
	}

	@Test
	@Order(3)
	void upstreamErrorPropagatesIsError() {
		client = connect("/mcp/server/test-proxy");
		var result = client.callTool(McpSchema.CallToolRequest.builder("fail")
			.arguments(Map.of())
			.build());
		assertThat(result.isError()).isTrue();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("上游模拟失败");
	}

	@Test
	@Order(4)
	void subsetProviderExposesOnlySelectedTools() {
		client = connect("/mcp/server/test-proxy-subset");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("echo");
		var result = client.callTool(McpSchema.CallToolRequest.builder("echo")
			.arguments(Map.of("message", "子集"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("echo: 子集");
	}

	@Test
	@Order(5)
	void authHeadersInjectedFromConfiguration() {
		assertThat(testProxyMcpProvider.getAuthHeaders())
			.containsEntry("Authorization", "Bearer test-secret");
	}

	@Test
	@Order(6)
	void lifecycleDestroysUpstreamConnectionsGracefully() {
		// destroy 优雅关闭所有端点与上游连接，且重复调用幂等
		assertThatCode(registrar::destroy).doesNotThrowAnyException();
		assertThatCode(registrar::destroy).doesNotThrowAnyException();
	}

	/**
	 * 测试专用 ProxyMcpProvider：指向 {@code proxy.test.upstream-url} 配置的内嵌上游，
	 * 认证 token 从 {@code proxy.test.auth-token} 配置注入（示范 ADR-0007 配置驱动认证）。
	 */
	static class TestProxyMcpProvider extends ProxyMcpProvider {

		private final String upstreamUrl;
		private final String authToken;

		TestProxyMcpProvider(String upstreamUrl, String authToken) {
			this.upstreamUrl = upstreamUrl;
			this.authToken = authToken;
		}

		@Override
		public String getName() {
			return "test-proxy";
		}

		@Override
		public String getPath() {
			return "/mcp/server/test-proxy";
		}

		@Override
		public String getUpstreamUrl() {
			return upstreamUrl;
		}

		@Override
		public Map<String, String> getAuthHeaders() {
			return Map.of("Authorization", "Bearer " + authToken);
		}

	}

	/**
	 * 测试专用 ProxyMcpProvider：固定只透传 echo 工具（ADR-0007 决策 3 的子集场景）。
	 */
	static class TestSubsetProxyMcpProvider extends ProxyMcpProvider {

		private final String upstreamUrl;

		TestSubsetProxyMcpProvider(String upstreamUrl) {
			this.upstreamUrl = upstreamUrl;
		}

		@Override
		public String getName() {
			return "test-proxy-subset";
		}

		@Override
		public String getPath() {
			return "/mcp/server/test-proxy-subset";
		}

		@Override
		public String getUpstreamUrl() {
			return upstreamUrl;
		}

		@Override
		public List<String> getToolNames() {
			return List.of("echo");
		}

	}

	/**
	 * 仅本测试 context 注册代理端点，避免影响其它集成测试。
	 */
	@Configuration(proxyBeanMethods = false)
	static class ProxyEndpointTestConfig {

		@Bean
		TestProxyMcpProvider testProxyMcpProvider(@Value("${proxy.test.upstream-url}") String upstreamUrl,
				@Value("${proxy.test.auth-token}") String authToken) {
			return new TestProxyMcpProvider(upstreamUrl, authToken);
		}

		@Bean
		TestSubsetProxyMcpProvider testSubsetProxyMcpProvider(
				@Value("${proxy.test.upstream-url}") String upstreamUrl) {
			return new TestSubsetProxyMcpProvider(upstreamUrl);
		}

	}

}
