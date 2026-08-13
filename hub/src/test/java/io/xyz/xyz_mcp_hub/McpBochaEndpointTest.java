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
 * Bocha 原生源集成测试（#39 迁移：旧多端点 {@code /mcp/builtin/bocha} 已移除，改经单端点
 * {@code /xyz-hub/mcp?includes=[bocha*]} 暴露，工具名带 {@code bocha_} 前缀）。
 *
 * <p>#63 主缝（端到端）：用 JDK 内置 {@link HttpServer} 起一个本地 mock 博查 API，并通过
 * {@code bocha.url} 指向它——工具调用不依赖真实 API key 与外部网络，但完整走通
 * 单端点 → {@code BochaTools} → HTTP 的调用链。覆盖：工具暴露名 {@code bocha_search}、{@code type}
 * 路由（默认 ai / type=web）、默认值生效、模态卡与追问问题从 HTTP 到返回的完整链。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpBochaEndpointTest {

	private static final String WEB_SEARCH_RESPONSE = """
		{"code":200,"data":{"webPages":{"value":[{"name":"Spring Boot 官网","url":"https://spring.io/projects/spring-boot","snippet":"快速构建生产级 Spring 应用。","siteName":"Spring"}]}}}
		""".strip();

	private static final String AI_SEARCH_RESPONSE = """
		{"code":200,"log_id":"test-log","messages":[
			{"role":"assistant","type":"answer","content_type":"text","content":"Spring Boot 是流行的 Java 微服务框架。"},
			{"role":"assistant","type":"source","content_type":"webpage","content":"{\\"webSearchUrl\\":\\"https://bochaai.com/search?q=spring boot\\",\\"value\\":[{\\"name\\":\\"Spring Boot 官网\\",\\"url\\":\\"https://spring.io/projects/spring-boot\\",\\"snippet\\":\\"快速构建生产级 Spring 应用。\\",\\"siteName\\":\\"Spring\\"}]}"}
		]}
		""".strip();

	/** ai 含模态卡（weather_china）与追问问题（follow_up），验证整链透传。 */
	private static final String AI_MODAL_CARD_RESPONSE = """
		{"code":200,"log_id":"modal-test","messages":[
			{"role":"assistant","type":"answer","content_type":"text","content":"杭州今日晴，25℃。"},
			{"role":"assistant","type":"source","content_type":"weather_china","content":"[{\\"modelCard\\":{\\"city\\":\\"杭州\\",\\"weather\\":\\"晴\\",\\"temperature\\":\\"25℃\\"}}]"},
			{"role":"assistant","type":"follow_up","content_type":"text","content":"[\\"杭州明天天气如何？\\",\\"杭州未来一周天气趋势？\\"]"}
		]}
		""".strip();

	private static HttpServer mockApi;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@DynamicPropertySource
	static void bochaBaseUrl(DynamicPropertyRegistry registry) throws IOException {
		mockApi = HttpServer.create(new InetSocketAddress(0), 0);
		mockApi.createContext("/v1/web-search", exchange -> respond(exchange, WEB_SEARCH_RESPONSE));
		mockApi.createContext("/v1/ai-search", exchange -> respond(exchange, AI_MODAL_CARD_RESPONSE));
		mockApi.start();
		registry.add("bocha.url", () -> "http://localhost:" + mockApi.getAddress().getPort());
		// 端点按 api-key 非空才注册（ADR-0005），测试注入假 key 使 bocha 端点生效
		registry.add("bocha.api-key", () -> "test-key");
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
			.endpoint("/xyz-hub/mcp?includes=[bocha*]")
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
	void listToolsExposesSingleSearchTool() {
		client = connect();
		var tools = client.listTools().tools();
		// #63 单 search 工具：暴露名 bocha_search（web_search/ai_search 已合并）
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("bocha_search");
		assertThat(tools).allSatisfy(tool -> assertThat(tool.description()).isNotBlank());
	}

	@Test
	void searchDefaultTypeReturnsAiSummaryAndModelCard() {
		client = connect();
		String text = callText("bocha_search", Map.of("query", "杭州天气"));
		// 默认 type=ai：AI 总结 + 模态卡 + 追问问题整链
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("杭州今日晴");
		assertThat(text).contains("模态卡 · weather_china");
		// modelCard 结构化 JSON 经 MCP callTool 返回时引号被转义为 \"（Spring AI @Tool 字符串返回值经
		// MCP 层 JSON 转义；能力层不经 MCP，BochaClientTest 断言未转义形式）
		assertThat(text).contains("\\\"city\\\":\\\"杭州\\\"");
		assertThat(text).contains("追问问题");
		assertThat(text).contains("1. 杭州明天天气如何？");
	}

	@Test
	void searchWebTypeReturnsResults() {
		client = connect();
		String text = callText("bocha_search", Map.of("type", "web", "query", "spring boot"));
		assertThat(text).contains("Spring Boot 官网");
		assertThat(text).contains("spring.io/projects/spring-boot");
		assertThat(text).contains("快速构建生产级");
	}

}
