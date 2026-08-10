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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组合源集成测试（ADR-0011 / issue #33）：{@code mcp.specs} 配置的组合源经真实 HTTP 端点验证——
 * 目录中出现 {@code type: composite} 新源（带 {@code base} 溯源、解析出的工具），
 * {@code includes=组合源} 与普通源一致生效、嵌套组合源生效。循环定义 fail-fast 与解析边界由
 * 纯单测（{@code CompositeSourceRegistryTest}）覆盖，本类不重复。
 *
 * <p>主 seam：经 {@code /xyz-hub/mcp}（MCP client）与 {@code /xyz-hub/catalog}（HTTP）验证；
 * 组合源定义经 {@code @DynamicPropertySource} 注入（{@code readonly} = bocha+utils 减
 * bocha_ai_search；{@code base-spec}→{@code nested} 为嵌套示例）。无外部依赖：bocha 上游用 JDK
 * {@link HttpServer} mock（同 {@code McpSingleEndpointTest} 手法）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpCompositeSourceIntegrationTest {

	private static final String WEB_SEARCH_RESPONSE = """
		{"code":200,"data":{"webPages":{"value":[{"name":"Spring Boot 官网","url":"https://spring.io/projects/spring-boot","snippet":"快速构建生产级 Spring 应用。","siteName":"Spring"}]}}}
		""".strip();

	private static final String AI_SEARCH_RESPONSE = """
		{"code":200,"log_id":"test-log","messages":[
			{"role":"assistant","type":"answer","content_type":"text","content":"Spring Boot 是流行的 Java 微服务框架。"},
			{"role":"assistant","type":"source","content_type":"webpage","content":"{\\"webSearchUrl\\":\\"https://bochaai.com/search?q=spring boot\\",\\"value\\":[{\\"name\\":\\"Spring Boot 官网\\",\\"url\\":\\"https://spring.io/projects/spring-boot\\",\\"snippet\\":\\"快速构建生产级 Spring 应用。\\",\\"siteName\\":\\"Spring\\"}]}"}
		]}
		""".strip();

	private static HttpServer mockApi;

	@LocalServerPort
	private int port;

	private static final JsonMapper jsonMapper = JsonMapper.builder().build();

	private McpSyncClient client;

	@DynamicPropertySource
	static void compositeSpecsAndBochaMock(DynamicPropertyRegistry registry) throws IOException {
		// 组合源定义（mcp.specs）：readonly = bocha+utils 减 bocha_ai_search；base-spec → nested 嵌套
		registry.add("mcp.specs.readonly.includes", () -> "bocha,utils");
		registry.add("mcp.specs.readonly.excludes", () -> "bocha_ai_search");
		registry.add("mcp.specs.base-spec.includes", () -> "bocha");
		registry.add("mcp.specs.nested.includes", () -> "base-spec,utils");
		registry.add("mcp.specs.nested.excludes", () -> "bocha_ai_search");
		// bocha 上游 mock + 假 key（ADR-0005：api-key 非空 bocha 源才注册）
		mockApi = HttpServer.create(new InetSocketAddress(0), 0);
		mockApi.createContext("/v1/web-search", exchange -> respond(exchange, WEB_SEARCH_RESPONSE));
		mockApi.createContext("/v1/ai-search", exchange -> respond(exchange, AI_SEARCH_RESPONSE));
		mockApi.start();
		registry.add("bocha.base-url", () -> "http://localhost:" + mockApi.getAddress().getPort());
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

	private McpSyncClient connect(String endpoint) {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint(endpoint)
			.build();
		var c = McpClient.sync(transport).build();
		c.initialize();
		return c;
	}

	private List<String> toolNames() {
		return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
	}

	// ---- 验收：组合源出现在目录 API 中（type: composite，带 base 溯源） ----

	@Test
	void catalogListsCompositeSourcesWithTypeCompositeAndBaseTracing() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		JsonNode readonly = sourceByName(sources, "readonly");
		assertThat(readonly.get("type").asText()).isEqualTo("composite");
		assertThat(readonly.get("scope").asText()).isEqualTo("network");
		JsonNode base = readonly.get("base");
		assertThat(base).isNotNull();
		assertThat(toStrings(base.get("includes"))).containsExactlyInAnyOrder("bocha", "utils");
		assertThat(toStrings(base.get("excludes"))).containsExactly("bocha_ai_search");
		// 工具为解析出的底层工具（普通源工具名）
		assertThat(toolNamesOf(readonly)).containsExactly("bocha_web_search", "utils_currentDateTime");

		// 嵌套：nested 引用了 base-spec + utils
		JsonNode nested = sourceByName(sources, "nested");
		assertThat(nested.get("type").asText()).isEqualTo("composite");
		assertThat(toolNamesOf(nested)).containsExactly("bocha_web_search", "utils_currentDateTime");
		JsonNode baseSpec = sourceByName(sources, "base-spec");
		assertThat(baseSpec.get("type").asText()).isEqualTo("composite");
		assertThat(toolNamesOf(baseSpec)).containsExactly("bocha_ai_search", "bocha_web_search");
	}

	// ---- 验收：includes=组合源 与普通源一致生效 ----

	@Test
	void includesCompositeNameExposesOnlyResolvedTools() {
		client = connect("/xyz-hub/mcp?includes=[readonly]");
		assertThat(toolNames()).containsExactlyInAnyOrder("bocha_web_search", "utils_currentDateTime");
	}

	@Test
	void nestedCompositeIsReferencedLikePlainSource() {
		client = connect("/xyz-hub/mcp?includes=[nested]");
		assertThat(toolNames()).containsExactlyInAnyOrder("bocha_web_search", "utils_currentDateTime");
	}

	@Test
	void compositeNameWorksInExcludesAgainstFullSet() {
		client = connect("/xyz-hub/mcp?excludes=[readonly]");
		List<String> names = toolNames();
		// readonly = bocha+utils 减 bocha_ai_search，故全量减 readonly 后 bocha_ai_search 仍在
		// （#38 fetch 门面已退役，全量不再含 fetch_fetch）
		assertThat(names).doesNotContain("bocha_web_search", "utils_currentDateTime");
		assertThat(names).contains("bocha_ai_search");
	}

	@Test
	void compositeCanBeCalledThroughMcp() {
		client = connect("/xyz-hub/mcp?includes=[readonly]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("bocha_web_search")
			.arguments(java.util.Map.of("query", "spring boot"))
			.build());
		assertThat(result.isError()).isFalse();
		assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("Spring Boot 官网");
	}

	private JsonNode fetchCatalog() throws Exception {
		HttpClient httpClient = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/xyz-hub/catalog"))
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return jsonMapper.readTree(response.body());
	}

	private static List<String> toolNamesOf(JsonNode source) {
		return toStrings(source.get("tools"));
	}

	private static List<String> toStrings(JsonNode array) {
		List<String> result = new ArrayList<>();
		array.forEach(item -> result.add(item.asText()));
		return result;
	}

	private static JsonNode sourceByName(JsonNode sources, String name) {
		for (JsonNode source : sources) {
			if (name.equals(source.get("name").asText())) {
				return source;
			}
		}
		throw new AssertionError("目录缺少源：" + name);
	}

}
