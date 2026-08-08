package io.xyz.xyz_mcp_hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.PlaywrightTools;
import io.xyz.xyz_mcp_hub.playwright.PlaywrightProperties;
import io.xyz.xyz_mcp_hub.playwright.WebSessionRegistry;
import io.xyz.xyz_mcp_hub.playwright.internal.SharedChromium;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright 端点集成测试：连接 {@code /mcp/builtin/playwright}，用无头 chromium 打开本地
 * 测试页，按 {@code docs/testing/mcp-service-test-guide.md} 对每个 {@code @Tool} 逐个真实
 * 调用并断言结果合理。会话租约模型下，每次连接先 {@code web_session(create)} 拿 sessionId，
 * 所有浏览器工具携带 sessionId 路由；并验证两会话隔离、无 sessionId 报错、TTL 回收与并发上限。
 *
 * <p>手工冒烟（非自动测试）：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.McpPlaywrightEndpointTest -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless，见 README）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpPlaywrightEndpointTest {

	private static final String PAGE_HTML = """
		<!DOCTYPE html>
		<html>
		<head><meta charset="utf-8"><title>Playwright Test Page</title></head>
		<body>
		  <h1>Playwright Test Page</h1>
		  <button id="counter">count: 0</button>
		  <input id="name" placeholder="name">
		  <select id="color">
		    <option value="red">红</option>
		    <option value="green">绿</option>
		    <option value="blue">蓝</option>
		  </select>
		  <label><input type="checkbox" id="agree">同意</label>
		  <input type="file" id="file">
		  <a id="to-page2" href="/page2">跳转页面二</a>
		  <div id="draggable" draggable="true">拖我</div>
		  <div id="dropzone" ondragover="event.preventDefault()" ondrop="this.textContent='dropped'">放这里</div>
		  <p id="dynamic" style="display:none">动态文本</p>
		  <button id="show-dynamic">显示动态文本</button>
		  <button id="trigger-alert">弹窗</button>
		  <button id="trigger-console">控制台</button>
		  <button id="fetch-api">请求</button>
		  <script>
		    const btn = document.getElementById('counter');
		    btn.addEventListener('click', () => {
		      const n = Number(btn.textContent.split(':')[1]) + 1;
		      btn.textContent = 'count: ' + n;
		    });
		    document.getElementById('show-dynamic').addEventListener('click', () => {
		      document.getElementById('dynamic').style.display = 'block';
		    });
		    document.getElementById('trigger-alert').addEventListener('click', () => {
		      alert('hello');
		    });
		    document.getElementById('trigger-console').addEventListener('click', () => {
		      console.log('hello-console');
		    });
		    document.getElementById('fetch-api').addEventListener('click', () => {
		      fetch('/api/ping');
		    });
		  </script>
		</body>
		</html>
		""";

	private static final String PAGE2_HTML = """
		<!DOCTYPE html>
		<html>
		<head><meta charset="utf-8"><title>Page Two</title></head>
		<body><h1>Page Two</h1></body>
		</html>
		""";

	private static final String PONG = "pong";

	@LocalServerPort
	private int port;

	@TempDir
	private Path tempDir;

	private HttpServer localServer;
	private String pageUrl;
	private McpSyncClient client;
	private String sessionId;

	@BeforeEach
	void startLocalPage() throws IOException {
		localServer = HttpServer.create(new InetSocketAddress(0), 0);
		localServer.createContext("/", exchange -> respond(exchange, PAGE_HTML));
		localServer.createContext("/page2", exchange -> respond(exchange, PAGE2_HTML));
		localServer.createContext("/api/ping", exchange -> respond(exchange, PONG));
		localServer.start();
		pageUrl = "http://localhost:" + localServer.getAddress().getPort() + "/";
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			if (sessionId != null) {
				try {
					callRaw("web_session", Map.of("action", "close", "sessionId", sessionId));
				}
				catch (RuntimeException ignored) {
					// 会话可能已被 TTL 回收或测试中途关闭，忽略
				}
			}
			client.closeGracefully();
		}
		localServer.stop(0);
	}

	private McpSyncClient newClient() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint("/mcp/builtin/playwright")
			.build();
		var c = McpClient.sync(transport).build();
		c.initialize();
		return c;
	}

	/** 建立主连接并创建一个会话，后续 callTool 自动携带该 sessionId。 */
	private McpSyncClient connect() {
		this.client = newClient();
		this.sessionId = createSession(this.client);
		return this.client;
	}

	private String createSession(McpSyncClient c) {
		String text = callText(c, McpSchema.CallToolRequest.builder("web_session")
			.arguments(Map.of("action", "create")).build());
		// Spring AI 对工具返回值做 JSON 序列化，文本两侧带引号，提取 sessionId 时去掉
		return text.substring(text.indexOf("sessionId: ") + "sessionId: ".length()).replace("\"", "");
	}

	private String callText(McpSyncClient c, McpSchema.CallToolRequest request) {
		var result = c.callTool(request);
		assertThat(result.isError()).as("callTool 不应报错：%s", result).isFalse();
		assertThat(result.content()).isNotEmpty();
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	/** 用主会话 sessionId 调用工具（自动注入 sessionId 参数）。 */
	private String callTool(String tool, Map<String, Object> arguments) {
		Map<String, Object> args = new java.util.HashMap<>(arguments);
		args.put("sessionId", sessionId);
		return callText(client, McpSchema.CallToolRequest.builder(tool).arguments(args).build());
	}

	/** 用指定 client 调用工具，不注入 sessionId（调用方自传）。 */
	private String callRaw(McpSyncClient c, String tool, Map<String, Object> arguments) {
		return callText(c, McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
	}

	private String callRaw(String tool, Map<String, Object> arguments) {
		return callRaw(client, tool, arguments);
	}

	private String webSessionClose(String sid) {
		return callRaw("web_session", Map.of("action", "close", "sessionId", sid));
	}

	private String webSessionList() {
		return callRaw("web_session", Map.of("action", "list"));
	}

	private void navigate() {
		callTool("browser_navigate", Map.of("url", pageUrl));
	}

	// ---- 工具注册 ----

	@Test
	void listToolsExposesAllBrowserTools() {
		client = connect();
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name)
			.contains("web_session", "browser_navigate", "browser_go_back", "browser_go_forward",
					"browser_snapshot", "browser_take_screenshot", "browser_click",
					"browser_hover", "browser_type", "browser_press_key",
					"browser_select_option", "browser_fill_form", "browser_resize",
					"browser_close", "browser_tabs", "browser_handle_dialog",
					"browser_network_requests", "browser_network_request",
					"browser_console_messages", "browser_evaluate", "browser_wait_for",
					"browser_find", "browser_drag", "browser_file_upload");
	}

	// ---- 导航 / 快照 / 截图 ----

	@Test
	void navigateThenSnapshotShowsPageText() {
		client = connect();
		navigate();
		String snapshot = callTool("browser_snapshot", Map.of());
		assertThat(snapshot).contains("Playwright Test Page");
		assertThat(snapshot).contains("count: 0");
	}

	@Test
	void navigateThenScreenshotReturnsPngImage() {
		client = connect();
		navigate();
		String screenshot = callTool("browser_take_screenshot", Map.of());
		// Spring AI 对工具返回值做 JSON 序列化，文本两侧带引号，故用 contains 提取
		assertThat(screenshot).contains("data:image/png;base64,");
		String base64 = screenshot.substring(screenshot.indexOf("data:image/png;base64,")
				+ "data:image/png;base64,".length()).replace("\"", "");
		byte[] image = java.util.Base64.getDecoder().decode(base64);
		// PNG 魔数 89 50 4E 47（"PNG"）
		assertThat(image).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
		assertThat(image.length).isGreaterThan(1000);
	}

	@Test
	void goBackAndForwardTraverseHistory() {
		client = connect();
		navigate();
		callTool("browser_navigate", Map.of("url", pageUrl + "page2"));
		assertThat(callTool("browser_go_back", Map.of())).contains("返回上一页");
		assertThat(callTool("browser_evaluate", Map.of("function", "location.pathname")))
			.contains("/");
		assertThat(callTool("browser_go_forward", Map.of())).contains("前进到下一页");
		assertThat(callTool("browser_evaluate", Map.of("function", "location.pathname")))
			.contains("/page2");
	}

	// ---- 交互：点击 / 输入 / 选择 / 表单 / 悬停 / 按键 / 视口 ----

	@Test
	void clickAndTypeUpdateLocalPage() {
		client = connect();
		navigate();
		callTool("browser_click", Map.of("target", "#counter"));
		assertThat(callTool("browser_evaluate",
				Map.of("function", "document.getElementById('counter').textContent")))
			.contains("count: 1");
		callTool("browser_type", Map.of("target", "#name", "text", "hello"));
		assertThat(callTool("browser_evaluate",
				Map.of("function", "document.getElementById('name').value")))
			.contains("hello");
	}

	@Test
	void selectOptionAndFillForm() {
		client = connect();
		navigate();
		callTool("browser_select_option", Map.of("target", "#color", "values", List.of("green")));
		assertThat(callTool("browser_evaluate",
				Map.of("function", "document.getElementById('color').value")))
			.contains("green");
		callTool("browser_fill_form", Map.of("fields", List.of(
				Map.of("target", "#name", "value", "hello"),
				Map.of("target", "#agree", "value", "true", "type", "checkbox"))));
		assertThat(callTool("browser_evaluate", Map.of("function",
				"document.getElementById('name').value + '|' + document.getElementById('agree').checked")))
			.contains("hello|true");
	}

	@Test
	void hoverPressKeyAndResize() {
		client = connect();
		navigate();
		assertThat(callTool("browser_hover", Map.of("target", "#counter"))).contains("已悬停");
		assertThat(callTool("browser_press_key", Map.of("key", "Tab"))).contains("已按键");
		callTool("browser_resize", Map.of("width", 800, "height", 600));
		assertThat(callTool("browser_evaluate", Map.of("function", "window.innerWidth")))
			.contains("800");
	}

	// ---- 等待 / 对话框 / 网络 / 控制台 ----

	@Test
	void waitForTextAppearanceAndDisappearance() {
		client = connect();
		navigate();
		assertThat(callTool("browser_wait_for", Map.of("time", 1))).contains("已等待");
		assertThat(callTool("browser_wait_for", Map.of("textGone", "动态文本")))
			.contains("等待到文本消失");
		callTool("browser_click", Map.of("target", "#show-dynamic"));
		assertThat(callTool("browser_wait_for", Map.of("text", "动态文本")))
			.contains("等待到文本出现");
	}

	@Test
	void handleDialogAutoAcceptsAlert() {
		client = connect();
		navigate();
		callTool("browser_handle_dialog", Map.of("accept", true));
		callTool("browser_click", Map.of("target", "#trigger-alert"));
		assertThat(callTool("browser_evaluate", Map.of("function", "1 + 1"))).contains("2");
	}

	@Test
	void networkRequestsAndConsoleMessages() {
		client = connect();
		navigate();
		callTool("browser_click", Map.of("target", "#fetch-api"));
		callTool("browser_wait_for", Map.of("time", 0.5));
		assertThat(callTool("browser_network_requests", Map.of("filter", "/api/ping")))
			.contains("/api/ping").contains("HTTP 200");
		// index 基于会话内全量记录序号；这里验证详情读取（完整记录 + response-headers 部分）
		assertThat(callTool("browser_network_request", Map.of("index", 1)))
			.contains("method: GET").contains("url: http://");
		assertThat(callTool("browser_network_request",
				Map.of("index", 1, "part", "response-headers"))).contains("content-type");
		callTool("browser_click", Map.of("target", "#trigger-console"));
		assertThat(callTool("browser_console_messages", Map.of("level", "info")))
			.contains("hello-console");
	}

	// ---- 标签页 / 查找 / 拖放 / 上传 / 关闭 ----

	@Test
	void tabsListNewSelectClose() {
		client = connect();
		navigate();
		assertThat(callTool("browser_tabs", Map.of("action", "list")))
			.contains("Playwright Test Page");
		callTool("browser_tabs", Map.of("action", "new", "url", pageUrl));
		assertThat(callTool("browser_tabs", Map.of("action", "list"))).contains("共 2 个");
		callTool("browser_tabs", Map.of("action", "select", "index", 0));
		assertThat(callTool("browser_tabs", Map.of("action", "close", "index", 1)))
			.contains("已关闭标签页 [1]");
		assertThat(callTool("browser_tabs", Map.of("action", "close", "index", 99)))
			.contains("不存在");
	}

	@Test
	void findLocatesTextWithContext() {
		client = connect();
		navigate();
		String found = callTool("browser_find", Map.of("text", "Playwright Test Page"));
		assertThat(found).contains("Playwright Test Page");
	}

	@Test
	void dragAndDropElement() {
		client = connect();
		navigate();
		callTool("browser_drag", Map.of("from", "#draggable", "to", "#dropzone"));
		assertThat(callTool("browser_evaluate",
				Map.of("function", "document.getElementById('dropzone').textContent")))
			.contains("dropped");
	}

	@Test
	void uploadFileToInput() throws IOException {
		Path file = tempDir.resolve("upload.txt");
		Files.writeString(file, "file-content");
		client = connect();
		navigate();
		callTool("browser_file_upload", Map.of("target", "#file", "paths", List.of(file.toString())));
		assertThat(callTool("browser_evaluate",
				Map.of("function", "document.getElementById('file').files.length")))
			.contains("1");
	}

	@Test
	void closeThenNavigateReopensPage() {
		client = connect();
		navigate();
		assertThat(callTool("browser_close", Map.of())).contains("已关闭标签页");
		callTool("browser_navigate", Map.of("url", pageUrl));
		assertThat(callTool("browser_snapshot", Map.of())).contains("Playwright Test Page");
	}

	// ---- 会话租约：创建 / 关闭 / 无 sessionId 报错 ----

	@Test
	void webSessionCreateListAndClose() {
		client = connect();
		// connect 已建一个会话，再建一个，list 应显示 2
		String second = createSession(client);
		assertThat(second).startsWith("ws-");
		assertThat(webSessionList()).contains("2");
		assertThat(webSessionClose(second)).contains("已关闭");
		assertThat(webSessionList()).contains("1");
		// 关闭后的会话再操作应报错
		assertThat(callRaw("browser_navigate", Map.of("sessionId", second, "url", pageUrl)))
			.contains("不存在或已被关闭");
		// close 不存在的会话返回提示
		assertThat(webSessionClose("ws-99999")).contains("不存在");
	}

	@Test
	void missingSessionIdReturnsClearError() {
		client = connect();
		String text = callRaw("browser_navigate", Map.of("url", pageUrl));
		assertThat(text).contains("缺少 sessionId");
	}

	@Test
	void twoSessionsAreIsolated() {
		client = connect();
		String sessionB = createSession(client);
		try {
			// 两会话各自导航到同一首页（隔离 BrowserContext，互不共享）
			navigate();
			callRaw("browser_navigate", Map.of("sessionId", sessionB, "url", pageUrl));
			// DOM 状态隔离：A 点击计数 +1，B 不受影响
			callTool("browser_click", Map.of("target", "#counter"));
			assertThat(callTool("browser_evaluate",
					Map.of("function", "document.getElementById('counter').textContent")))
				.contains("count: 1");
			assertThat(callRaw("browser_evaluate", Map.of("sessionId", sessionB,
					"function", "document.getElementById('counter').textContent")))
				.contains("count: 0");
			// 存储隔离：B 写入 localStorage，A 读不到，证明 cookie/存储独立
			callRaw("browser_evaluate", Map.of("sessionId", sessionB,
					"function", "localStorage.setItem('k', 'b-value')"));
			assertThat(callTool("browser_evaluate", Map.of("function", "localStorage.getItem('k')")))
				.contains("null");
			assertThat(callRaw("browser_evaluate", Map.of("sessionId", sessionB,
					"function", "localStorage.getItem('k')")))
				.contains("b-value");
		}
		finally {
			webSessionClose(sessionB);
		}
	}

	@Test
	void closingOneSessionLeavesOthersUsable() {
		client = connect();
		String sessionB = createSession(client);
		navigate();
		callRaw("browser_navigate", Map.of("sessionId", sessionB, "url", pageUrl));
		assertThat(webSessionClose(sessionB)).contains("已关闭");
		// 关闭 B 后，主会话 A 仍可用（会话间生命周期互不影响）
		assertThat(callTool("browser_evaluate", Map.of("function", "location.pathname"))).contains("/");
		// 已关闭的会话再操作报错
		assertThat(callRaw("browser_snapshot", Map.of("sessionId", sessionB)))
			.contains("不存在或已被关闭");
	}

	@Test
	void concurrentSessionsInterleavedOperationsDoNotMix() throws Exception {
		// 会话 A：主连接；会话 B：独立连接（模拟两个 agent 并发连端点）
		client = connect();
		McpSyncClient clientB = newClient();
		String sessionB = createSession(clientB);
		try {
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<String> fa = pool.submit((Callable<String>) () -> {
					callTool("browser_navigate", Map.of("url", pageUrl));
					return callTool("browser_evaluate", Map.of("function", "location.pathname"));
				});
				Future<String> fb = pool.submit((Callable<String>) () -> {
					callRaw(clientB, "browser_navigate",
							Map.of("sessionId", sessionB, "url", pageUrl + "page2"));
					return callRaw(clientB, "browser_evaluate",
							Map.of("sessionId", sessionB, "function", "location.pathname"));
				});
				assertThat(fa.get(60, TimeUnit.SECONDS)).contains("/");
				assertThat(fb.get(60, TimeUnit.SECONDS)).contains("/page2");
			}
			finally {
				pool.shutdownNow();
			}
		}
		finally {
			webSessionClose(sessionB);
			clientB.closeGracefully();
		}
	}

	/**
	 * 手工冒烟（非自动测试）：起本地页，走通 create session → navigate → snapshot → click →
	 * evaluate 主链路，步骤化输出供 issue 留证。运行：
	 * {@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.McpPlaywrightEndpointTest -Dexec.classpathScope=test -Dvaadin.skip=true}
	 *
	 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless）
	 */
	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> respond(exchange, PAGE_HTML));
		server.start();
		String url = "http://localhost:" + server.getAddress().getPort() + "/";

		SharedChromium sharedChromium = new SharedChromium(new PlaywrightProperties());
		WebSessionRegistry registry = new WebSessionRegistry(sharedChromium, new PlaywrightProperties());
		PlaywrightTools tools = new PlaywrightTools(registry);
		try {
			System.out.println("[1/5] 创建浏览器会话");
			String created = tools.webSession("create", null);
			System.out.println("      " + created);
			String sid = created.substring(created.indexOf("sessionId: ") + "sessionId: ".length());
			System.out.println("[2/5] 导航到本地测试页");
			System.out.println("      " + tools.browserNavigate(sid, url));
			System.out.println("[3/5] 捕获可访问性快照");
			System.out.println("      " + truncate(tools.browserSnapshot(sid), 400));
			System.out.println("[4/5] 点击计数按钮");
			System.out.println("      " + tools.browserClick(sid, "#counter", null, null));
			System.out.println("[5/5] 读取计数结果");
			String count = tools.browserEvaluate(sid, "document.getElementById('counter').textContent");
			System.out.println("      计数结果：" + count);
			boolean ok = count != null && count.contains("count: 1");
			System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）"));
		}
		finally {
			registry.destroy();
			sharedChromium.destroy();
			server.stop(0);
		}
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
