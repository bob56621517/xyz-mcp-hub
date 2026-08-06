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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 公共 Proxy 端点集成测试（#6：context7 / grep.app / wikidata）：内嵌上游 MCP Server，
 * 经三个代理端点验证 listTools 透传与 callTool 透明转发。
 *
 * <p>三个提供者均为免认证的公共 Proxy（ADR-0007 一般免认证场景），测试经
 * {@code DynamicPropertySource} 把上游 URL 指向内嵌上游，验证配置注入而非直连生产。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpPublicProxyEndpointTest {

	private static final String[] PUBLIC_PATHS = {
			"/mcp/server/context7", "/mcp/server/grep-app", "/mcp/server/wikidata"
	};

	private static ConfigurableApplicationContext upstreamContext;

	@LocalServerPort
	private int port;

	@Autowired
	private List<ProxyMcpProvider> proxyProviders;

	@Autowired
	private HubMcpRegistrar registrar;

	private McpSyncClient client;

	@DynamicPropertySource
	static void registerUpstream(DynamicPropertyRegistry registry) {
		upstreamContext = new SpringApplicationBuilder(UpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		String upstreamUrl = "http://localhost:" + upstreamPort + "/mcp/server/upstream";
		registry.add("proxy.context7.upstream-url", () -> upstreamUrl);
		registry.add("proxy.grep-app.upstream-url", () -> upstreamUrl);
		registry.add("proxy.wikidata.upstream-url", () -> upstreamUrl);
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

	private void assertEndpointForwards(String path) {
		client = connect(path);
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("echo", "fail");
		var result = client.callTool(McpSchema.CallToolRequest.builder("echo")
			.arguments(Map.of("message", "你好"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("echo: 你好");
	}

	@Test
	@Order(1)
	void context7EndpointRegistersAndForwards() {
		assertEndpointForwards("/mcp/server/context7");
	}

	@Test
	@Order(2)
	void grepAppEndpointRegistersAndForwards() {
		assertEndpointForwards("/mcp/server/grep-app");
	}

	@Test
	@Order(3)
	void wikidataEndpointRegistersAndForwards() {
		assertEndpointForwards("/mcp/server/wikidata");
	}

	@Test
	@Order(4)
	void publicProvidersPointAtConfiguredUpstream() {
		assertThat(proxyProviders).filteredOn(p -> List.of(PUBLIC_PATHS).contains(p.getPath()))
			.hasSize(3)
			.allSatisfy(p -> assertThat(p.getUpstreamUrl()).startsWith("http://localhost:"));
	}

	@Test
	@Order(5)
	void publicProvidersSendNoAuthHeaders() {
		assertThat(proxyProviders).filteredOn(p -> List.of(PUBLIC_PATHS).contains(p.getPath()))
			.allSatisfy(p -> assertThat(p.getAuthHeaders()).isEmpty());
	}

	@Test
	@Order(6)
	void upstreamErrorPropagatesIsError() {
		client = connect("/mcp/server/context7");
		var result = client.callTool(McpSchema.CallToolRequest.builder("fail").arguments(Map.of()).build());
		assertThat(result.isError()).isTrue();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("上游模拟失败");
	}

	@Test
	@Order(10)
	void lifecycleDestroysUpstreamConnectionsGracefully() {
		// destroy 优雅关闭所有端点与上游连接，且重复调用幂等；须在 web server 关闭前执行，
		// 否则 Hub→上游的 SSE 订阅会让 Tomcat 优雅关闭空等 30s
		assertThatCode(registrar::destroy).doesNotThrowAnyException();
		assertThatCode(registrar::destroy).doesNotThrowAnyException();
	}

}
