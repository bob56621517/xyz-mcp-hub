package io.xyz.xyz_mcp_hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录 API 集成测试（ADR-0011 / issue #34）：{@code GET /xyz-hub/catalog} 返回已注册源的机器可读清单。
 *
 * <p>主 seam：经真实 HTTP 端点验证目录形状——每源含 name / type / protocol / scope / tools
 * （#49 组合源移除后不再有 base），与 ADR-0011 目录 schema 一致；冒烟断言当前已注册源
 * （utils / bocha 为 native，playwright 为 host）都在清单中。无认证：请求不带任何 Authorization 头
 * 即可读（本端点与 MCP 端点一致，仅本地可读）。</p>
 *
 * <p>无外部依赖：bocha 上游用 JDK {@link HttpServer} mock（同 {@code McpSingleEndpointTest} 手法），
 * 注入假 key 使 bocha 源注册进目录。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpCatalogEndpointTest {

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

	@DynamicPropertySource
	static void bochaMock(DynamicPropertyRegistry registry) throws IOException {
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

	// ---- 验收 1：目录返回所有已注册源及其工具（无认证、仅本地可读） ----
	// 冒烟（本期）：已注册源集随环境抖动（配置了 GITHUB_TOKEN 时 github-full 也注册），
	// 精确源集断言在 #48「注册/启用分离」后重写（目录固定列所有已注册源 + enabled），见 @Disabled 理由。
	// playwright 为 HostMcp 源（type=host，issue #36）；其余（bocha/utils）为 native。

	@Test
	@org.junit.jupiter.api.Disabled("#48 注册/启用分离后重写：配置了 GITHUB_TOKEN 时 github-full 也注册进目录，"
			+ "精确源集断言依赖环境，见 issue #48 Problem Statement")
	void catalogListsAllRegisteredNativeSources() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		assertThat(sources.isArray()).isTrue();
		assertThat(names(sources)).containsExactlyInAnyOrder("bocha", "playwright", "utils");
		for (JsonNode source : sources) {
			String expected = "playwright".equals(source.get("name").asText()) ? "host" : "native";
			assertThat(source.get("type").asText()).isEqualTo(expected);
		}
	}

	// ---- 验收 2：各源含 type / protocol / scope / tools；组合源含 base（#49 移除后不再有） ----
	// 形状契约（与具体源无关，目录自动增长后仍应成立）

	@Test
	void eachSourceCarriesValidCatalogSchema() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		for (JsonNode source : sources) {
			assertThat(source.get("name").asText()).isNotBlank();
			// type 为合法的源类型小写取值（native/proxy/container/host，#49 组合源已移除）
			assertThat(source.get("type").asText())
				.isIn("native", "proxy", "container", "host");
			// protocol：container 专有（mcp|rest），其余为 null
			JsonNode protocol = source.get("protocol");
			assertThat(protocol.isNull() || protocol.asText().matches("mcp|rest")).isTrue();
			// scope：host / network 小写
			assertThat(source.get("scope").asText()).isIn("host", "network");
			// base：组合源溯源已随 #49 整体移除，目录 schema 契约即不含 base 字段
			assertThat(source.has("base")).isFalse();
			// tools：非空工具名数组
			assertThat(source.get("tools").isArray()).isTrue();
			assertThat(toolNames(source)).isNotEmpty();
		}
	}

	// ---- 冒烟：bocha / utils 的工具为带 {source}_ 前缀的注册名 ----

	@Test
	void bochaToolsArePrefixedAndSorted() throws Exception {
		JsonNode bocha = sourceByName(fetchCatalog().get("sources"), "bocha");
		assertThat(toolNames(bocha)).containsExactly("bocha_ai_search", "bocha_web_search");
	}

	@Test
	void utilsToolsArePrefixedWithSourceName() throws Exception {
		JsonNode utils = sourceByName(fetchCatalog().get("sources"), "utils");
		assertThat(toolNames(utils)).contains("utils_currentDateTime");
		assertThat(toolNames(utils)).allMatch(name -> name.startsWith("utils_"));
	}

	@Test
	void playwrightSourceIsListedWithTools() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		JsonNode playwright = sourceByName(sources, "playwright");
		assertThat(toolNames(playwright)).contains("playwright_web_session", "playwright_browser_navigate");
	}

	private JsonNode fetchCatalog() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/xyz-hub/catalog"))
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
			.as("Content-Type")
			.isEqualTo("application/json");
		return jsonMapper.readTree(response.body());
	}

	private static List<String> names(JsonNode sources) {
		List<String> result = new ArrayList<>();
		sources.forEach(source -> result.add(source.get("name").asText()));
		return result;
	}

	private static List<String> toolNames(JsonNode source) {
		List<String> result = new ArrayList<>();
		source.get("tools").forEach(tool -> result.add(tool.asText()));
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
