package io.xyz.xyz_mcp_hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bocha 端点集成测试：经 {@code /mcp/server/bocha} 端点验证工具注册与真实调用。
 *
 * <p>用 JDK 内置 {@link HttpServer} 起一个本地 mock 博查 API，并通过
 * {@code bocha.base-url} 指向它——工具调用不依赖真实 API key 与外部网络，但完整走通
 * MCP 端点 → {@code BochaTools} → HTTP 的调用链。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpBochaEndpointTest {

	private static final String WEB_SEARCH_RESPONSE = """
		{"code":200,"data":{"webPages":{"value":[{"name":"Spring Boot 官网","url":"https://spring.io/projects/spring-boot","snippet":"快速构建生产级 Spring 应用。","siteName":"Spring"}]}}}
		""".strip();

	private static final String AI_SEARCH_RESPONSE = """
		{"code":200,"data":{"aiSummary":"Spring Boot 是流行的 Java 微服务框架。","webPages":{"value":[{"name":"Spring Boot 官网","url":"https://spring.io/projects/spring-boot","snippet":"快速构建生产级 Spring 应用。","siteName":"Spring"}]}}}
		""".strip();

	private static HttpServer mockApi;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@DynamicPropertySource
	static void bochaBaseUrl(DynamicPropertyRegistry registry) throws IOException {
		mockApi = HttpServer.create(new InetSocketAddress(0), 0);
		mockApi.createContext("/v1/web-search", exchange -> respond(exchange, WEB_SEARCH_RESPONSE));
		mockApi.createContext("/v1/ai-search", exchange -> respond(exchange, AI_SEARCH_RESPONSE));
		mockApi.start();
		registry.add("bocha.base-url", () -> "http://localhost:" + mockApi.getAddress().getPort());
	}

	private static void respond(HttpExchange exchange, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@AfterAll
	static void stopMockApi() {
		if (mockApi != null) {
			mockApi.stop(0);
		}
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully();
		}
	}

	private McpSyncClient connect() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint("/mcp/server/bocha")
			.build();
		var client = McpClient.sync(transport).build();
		client.initialize();
		return client;
	}

	private String callText(String toolName, Map<String, Object> arguments) {
		var result = client.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
		assertThat(result.isError()).isFalse();
		assertThat(result.content()).isNotEmpty();
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	@Test
	void listToolsExposesBochaSearchTools() {
		client = connect();
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).contains("web_search", "ai_search");
		assertThat(tools).allSatisfy(tool -> assertThat(tool.description()).isNotBlank());
	}

	@Test
	void callWebSearchReturnsResults() {
		client = connect();
		String text = callText("web_search", Map.of("query", "spring boot"));
		assertThat(text).contains("Spring Boot 官网");
		assertThat(text).contains("spring.io/projects/spring-boot");
		assertThat(text).contains("快速构建生产级");
	}

	@Test
	void callAiSearchReturnsSummary() {
		client = connect();
		String text = callText("ai_search", Map.of("query", "spring boot", "count", 5));
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("流行的 Java 微服务框架");
		assertThat(text).contains("Spring Boot 官网");
	}

}
