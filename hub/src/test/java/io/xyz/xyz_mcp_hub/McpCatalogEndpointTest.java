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
 * 目录 API 集成测试（ADR-0011 / issue #34，#50 重构；ADR-0016 三型收敛）：{@code GET /xyz-hub/catalog}
 * 返回全部已注册源的机器可读清单。
 *
 * <p>主 seam：经真实 HTTP 端点验证目录形状——每源含 name / type / scope / enabled / tools，与 ADR-0011
 * 目录 schema 一致（#49 组合源移除后无 base；#50 host 并入 native；ADR-0016 容器型溶解后无 protocol、
 * type 只剩 native/proxy）。无认证：请求不带任何 Authorization 头即可读（仅本地可读）。</p>
 *
 * <p>源集冻结（#50 注册/启用分离）：目录列出**所有已注册源**（代码/配置固定），enabled 反映配置门控——
 * bocha 自给 mock key（enabled=true）、github auth-header 置空（enabled=false）、proxy 指向不可达
 * （enabled=true 但工具空）、jina.url 置空（native 源 enabled=false）。断言不随外部环境
 * （GITHUB_AUTH_HEADER / 网络 / 引擎）抖动。</p>
 *
 * <p>无外部依赖：bocha 上游用 JDK {@link HttpServer} mock（同 {@code McpSingleEndpointTest} 手法）；
 * proxy（context7/grep-app/wikidata）上游全部指向不可达地址；jina.url 置空不连真实引擎。</p>
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

	/** 不可达 proxy 上游（localhost:1 必然 Connection refused），用于冻结 proxy 源集且不触网。 */
	private static final String UNREACHABLE = "http://localhost:1/mcp";

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
		registry.add("bocha.url", () -> "http://localhost:" + mockApi.getAddress().getPort());
		// bocha：opt-in 源，测试自给 mock key → enabled=true
		registry.add("bocha.api-key", () -> "test-key");
		// #52 配置驱动：完整 mcp.proxies 列表（app-props 已置空，须显式提供全部条目，源集冻结）。
		// 三个公共 proxy 源指向不可达（enabled=true 但工具空，不触网）；github auth 置空 → enabled=false
		registry.add("mcp.proxies[0].name", () -> "context7");
		registry.add("mcp.proxies[0].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[1].name", () -> "grep-app");
		registry.add("mcp.proxies[1].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[2].name", () -> "wikidata");
		registry.add("mcp.proxies[2].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[3].name", () -> "github");
		registry.add("mcp.proxies[3].upstream-url", () -> UNREACHABLE);
		registry.add("mcp.proxies[3].auth-header", () -> "");
		// jina：native 源，端点配置化（ADR-0016）。测试置空 jina.url → enabled=false（不连真实引擎）
		registry.add("jina.url", () -> "");
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

	// ---- 验收 1：目录列出所有已注册源（源集冻结，不随环境抖动）+ enabled 显式声明 ----

	@Test
	void catalogListsAllRegisteredSourcesWithFrozenSet() throws Exception {
		// 已注册源集 = 代码/配置固定（#50，ADR-0016 去 markitdown）：native（utils/bocha/playwright/jina）
		// + proxy（github/context7/grep-app/wikidata），未启用源也列出
		JsonNode sources = fetchCatalog().get("sources");
		assertThat(sources.isArray()).isTrue();
		assertThat(names(sources)).containsExactlyInAnyOrder(
				"bocha", "utils", "playwright", "jina",
				"github", "context7", "grep-app", "wikidata");
	}

	@Test
	void optInSourcesDeclareEnabledExplicitly() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		// bocha：自给 mock key → enabled=true、工具可用
		JsonNode bocha = sourceByName(sources, "bocha");
		assertThat(bocha.get("enabled").asBoolean()).isTrue();
		assertThat(toolNames(bocha)).isNotEmpty();
		// github：auth-header 置空 → enabled=false、tools 空
		JsonNode github = sourceByName(sources, "github");
		assertThat(github.get("enabled").asBoolean()).isFalse();
		assertThat(toolNames(github)).isEmpty();
	}

	@Test
	void unenabledSourcesAreListedWithEmptyTools() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		// 未启用源（github / jina，jina.url 置空）目录列出、enabled=false、tools 空
		for (String name : List.of("github", "jina")) {
			JsonNode source = sourceByName(sources, name);
			assertThat(source.get("enabled").asBoolean()).as("源 %s 应未启用", name).isFalse();
			assertThat(toolNames(source)).as("源 %s 工具应为空", name).isEmpty();
		}
		// 已启用源（utils/playwright）enabled=true、工具非空
		for (String name : List.of("utils", "playwright")) {
			JsonNode source = sourceByName(sources, name);
			assertThat(source.get("enabled").asBoolean()).as("源 %s 应启用", name).isTrue();
			assertThat(toolNames(source)).as("源 %s 工具应非空", name).isNotEmpty();
		}
	}

	// ---- 验收 2：各源含 type / scope / enabled / tools；type 只剩 native/proxy（ADR-0016） ----

	@Test
	void eachSourceCarriesValidCatalogSchema() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		for (JsonNode source : sources) {
			assertThat(source.get("name").asText()).isNotBlank();
			// type 为合法的源类型小写取值（native/proxy，#49 组合源、#50 host、ADR-0016 container 已收敛）
			assertThat(source.get("type").asText()).isIn("native", "proxy");
			// protocol：容器型已溶解（ADR-0016），目录不再有 protocol 字段
			assertThat(source.has("protocol")).as("目录不应再有 protocol 字段").isFalse();
			// scope：host / network 小写
			assertThat(source.get("scope").asText()).isIn("host", "network");
			// enabled：布尔字段（注册/启用分离，#50）
			assertThat(source.get("enabled")).isNotNull();
			// base：组合源溯源已随 #49 整体移除，目录 schema 契约即不含 base 字段
			assertThat(source.has("base")).isFalse();
			// tools：工具名数组（未启用源为空）
			assertThat(source.get("tools").isArray()).isTrue();
		}
	}

	// ---- 冒烟：bocha / utils / playwright 的工具与类型 ----

	@Test
	void bochaToolsArePrefixedAndSorted() throws Exception {
		JsonNode bocha = sourceByName(fetchCatalog().get("sources"), "bocha");
		// #63 单 search 工具：目录列出 bocha_search
		assertThat(toolNames(bocha)).containsExactly("bocha_search");
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
		// host 并入 native（#50）：type=native、scope=host 表达部署
		assertThat(playwright.get("type").asText()).isEqualTo("native");
		assertThat(playwright.get("scope").asText()).isEqualTo("host");
		assertThat(toolNames(playwright)).contains("playwright_web_session", "playwright_browser_navigate");
	}

	// ---- 冒烟：proxy / jina 的类型（ADR-0016 三型收敛） ----

	@Test
	void proxySourcesAreListedAsProxyType() throws Exception {
		JsonNode sources = fetchCatalog().get("sources");
		for (String name : List.of("github", "context7", "grep-app", "wikidata")) {
			assertThat(sourceByName(sources, name).get("type").asText()).as("源 %s 应 type=proxy", name)
				.isEqualTo("proxy");
		}
	}

	@Test
	void jinaIsListedAsNativeType() throws Exception {
		// jina 归 native（ADR-0016：端点配置化 + 薄 HTTP 包装；容器型已溶解）
		JsonNode jina = sourceByName(fetchCatalog().get("sources"), "jina");
		assertThat(jina.get("type").asText()).isEqualTo("native");
		assertThat(jina.get("scope").asText()).isEqualTo("network");
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
