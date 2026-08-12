package io.xyz.xyz_mcp_hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单端点 + URL 参数工具视图集成测试（ADR-0011，issue #30）。
 *
 * <p>主 seam：经真实 HTTP 端点验证 {@code /xyz-hub/mcp}（Streamable HTTP）与 {@code /xyz-hub/sse}
 * （HTTP+SSE）双传输共享的 URL 参数过滤行为（#51 严格语义）：includes/excludes 工具名通配
 * （源名匹配退役）/ 精确 / [a,b] 列表、参数缺失 = 全量、显式 {@code includes=[]} = 空集、未知项
 * 静默忽略 + warn、被过滤工具 call 被拒。旧多端点并存由既有测试覆盖，本类不触碰旧端点。</p>
 *
 * <p>无外部依赖：bocha 上游用 JDK {@link HttpServer} mock（同 {@code McpBochaEndpointTest} 手法），
 * 不依赖真实公网与真实 key。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSingleEndpointTest {

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

	private McpSyncClient client;

	@DynamicPropertySource
	static void bochaMock(DynamicPropertyRegistry registry) throws IOException {
		mockApi = HttpServer.create(new InetSocketAddress(0), 0);
		mockApi.createContext("/v1/web-search", exchange -> respond(exchange, WEB_SEARCH_RESPONSE));
		mockApi.createContext("/v1/ai-search", exchange -> respond(exchange, AI_SEARCH_RESPONSE));
		mockApi.start();
		registry.add("bocha.base-url", () -> "http://localhost:" + mockApi.getAddress().getPort());
		// 源注册表按 api-key 非空才注册 bocha 源（ADR-0005），测试注入假 key 使 bocha 生效
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

	private McpSyncClient connectSse(String sseEndpoint) {
		// SSE 客户端的 HttpClient 在 closeGracefully 时不显式 close（SDK 现状），其默认执行器线程
		// 非 daemon 会拖住 JVM 退出；测试注入 daemon 执行器避免测试进程挂住
		var transport = HttpClientSseClientTransport.builder("http://localhost:" + port)
			.sseEndpoint(sseEndpoint)
			.clientBuilder(HttpClient.newBuilder()
				.executor(Executors.newCachedThreadPool(runnable -> {
					Thread thread = new Thread(runnable, "sse-test-client");
					thread.setDaemon(true);
					return thread;
				})))
			.build();
		var c = McpClient.sync(transport).build();
		c.initialize();
		return c;
	}

	private List<String> toolNames() {
		return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
	}

	// ---- 验收 1：includes 工具名通配（源名匹配退役，#51），[bocha*] 只暴露 bocha 工具 ----

	@Test
	void includesSourceWildcardExposesSourceToolsOnly() {
		client = connect("/xyz-hub/mcp?includes=[bocha*]");
		assertThat(toolNames()).containsExactly("bocha_search");
	}

	// ---- 验收：includes=[playwright*] 只暴露 HostMcp playwright 源浏览器工具集（issue #36） ----

	@Test
	void includesPlaywrightExposesHostMcpBrowserToolsOnly() {
		client = connect("/xyz-hub/mcp?includes=[playwright*]");
		assertThat(toolNames()).contains("playwright_web_session", "playwright_browser_navigate",
				"playwright_browser_snapshot", "playwright_browser_take_screenshot");
		assertThat(toolNames()).doesNotContain("bocha_search", "utils_currentDateTime");
	}

	// ---- 验收 2：无参数 = 全量工具（向后兼容） ----

	@Test
	void noParamsListsAllTools() {
		client = connect("/xyz-hub/mcp");
		List<String> names = toolNames();
		assertThat(names).contains("bocha_search", "utils_currentDateTime");
		// 全量 = 全部已注册源工具的超集，至少包含首迁移的两个原生源与 HostMcp playwright 源
		assertThat(names.size()).isGreaterThanOrEqualTo(3);
	}

	@Test
	void explicitEmptyIncludesIsEmptyToolSet() {
		// #51 严格语义：includes=[] = 空集（无语法糖），不引入任何工具——与「参数缺失 = 全量」严格区分
		client = connect("/xyz-hub/mcp?includes=[]&excludes=[]");
		assertThat(toolNames()).isEmpty();
	}

	// ---- 验收 3：includes/excludes 语法（工具名通配 / 精确 / [a,b] / 未知项忽略，#51） ----

	@Test
	void includesExactToolNameSelectsSingleTool() {
		client = connect("/xyz-hub/mcp?includes=[bocha_search]");
		assertThat(toolNames()).containsExactly("bocha_search");
	}

	@Test
	void includesListCombinesSourceAndTool() {
		client = connect("/xyz-hub/mcp?includes=[bocha_search,utils*]");
		assertThat(toolNames()).containsExactlyInAnyOrder("bocha_search", "utils_currentDateTime");
	}

	@Test
	void excludesSubtractsFromFullSet() {
		client = connect("/xyz-hub/mcp?excludes=[bocha_search]");
		List<String> names = toolNames();
		assertThat(names).contains("utils_currentDateTime").doesNotContain("bocha_search");
	}

	@Test
	void excludesSourceWildcardRemovesAllSourceTools() {
		client = connect("/xyz-hub/mcp?excludes=[bocha*]");
		List<String> names = toolNames();
		assertThat(names).doesNotContain("bocha_search");
		assertThat(names).contains("utils_currentDateTime");
	}

	@Test
	void includeThenExcludeExclusionWins() {
		client = connect("/xyz-hub/mcp?includes=[bocha*]&excludes=[bocha_search]");
		assertThat(toolNames()).isEmpty();
	}

	@Test
	void unknownItemIsSilentlyIgnored() {
		// 未知项静默忽略（+日志 warn），连接不失败，剩余项照常生效
		client = connect("/xyz-hub/mcp?includes=[no_such_source,no_such_tool,bocha*]");
		assertThat(toolNames()).containsExactly("bocha_search");
	}

	// ---- 验收 4：/xyz-hub/sse 与 /xyz-hub/mcp 过滤行为一致 ----

	@Test
	void sseEndpointFiltersConsistentlyWithMcp() {
		client = connectSse("/xyz-hub/sse?includes=[bocha*]");
		assertThat(toolNames()).containsExactly("bocha_search");
	}

	@Test
	void sseEndpointNoParamsListsAllTools() {
		client = connectSse("/xyz-hub/sse");
		List<String> names = toolNames();
		assertThat(names).contains("bocha_search", "utils_currentDateTime");
	}

	// ---- 调用路径：可见工具可调，被过滤工具对 agent 不存在（call 被拒） ----

	@Test
	void visibleToolCanBeCalled() {
		client = connect("/xyz-hub/mcp?includes=[bocha*]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("bocha_search")
			.arguments(Map.of("type", "web", "query", "spring boot"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).contains("Spring Boot 官网");
	}

	@Test
	void filteredOutToolCallIsRejected() {
		// 被过滤的工具对 agent「不存在」：call 返回 JSON-RPC 错误（与 SDK 未知工具行为一致）
		client = connect("/xyz-hub/mcp?includes=[bocha*]");
		assertThatThrownBy(() -> client.callTool(McpSchema.CallToolRequest.builder("utils_currentDateTime")
			.arguments(Map.of())
			.build()))
			.isInstanceOf(McpError.class);
	}

}
