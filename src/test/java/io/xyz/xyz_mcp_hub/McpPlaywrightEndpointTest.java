package io.xyz.xyz_mcp_hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.PlaywrightSession;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.PlaywrightTools;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright 端点集成测试：连接 {@code /mcp/server/playwright}，用无头 chromium 打开本地
 * 测试页，按 {@code docs/testing/mcp-service-test-guide.md} 对每个 {@code @Tool} 逐个真实
 * 调用并断言结果合理。
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
			client.closeGracefully();
		}
		localServer.stop(0);
	}

	private McpSyncClient connect() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint("/mcp/server/playwright")
			.build();
		var c = McpClient.sync(transport).build();
		c.initialize();
		return c;
	}

	private String callText(McpSchema.CallToolRequest request) {
		var result = client.callTool(request);
		assertThat(result.isError()).as("callTool 不应报错：%s", result).isFalse();
		assertThat(result.content()).isNotEmpty();
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	private String callTool(String tool, Map<String, Object> arguments) {
		return callText(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
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
			.contains("browser_navigate", "browser_go_back", "browser_go_forward",
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

	/**
	 * 手工冒烟（非自动测试）：起本地页，走通 navigate → snapshot → click → evaluate 主链路，
	 * 步骤化输出供 issue 留证。运行：
	 * {@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.McpPlaywrightEndpointTest -Dexec.classpathScope=test -Dvaadin.skip=true}
	 *
	 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless）
	 */
	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> respond(exchange, PAGE_HTML));
		server.start();
		String url = "http://localhost:" + server.getAddress().getPort() + "/";

		PlaywrightSession session = new PlaywrightSession(true, 30);
		PlaywrightTools tools = new PlaywrightTools(session);
		try {
			System.out.println("[1/4] 导航到本地测试页");
			System.out.println("      " + tools.browserNavigate(url));
			System.out.println("[2/4] 捕获可访问性快照");
			System.out.println("      " + truncate(tools.browserSnapshot(), 400));
			System.out.println("[3/4] 点击计数按钮");
			System.out.println("      " + tools.browserClick("#counter", null, null));
			System.out.println("[4/4] 读取计数结果");
			String count = tools.browserEvaluate("document.getElementById('counter').textContent");
			System.out.println("      计数结果：" + count);
			boolean ok = count != null && count.contains("count: 1");
			System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）"));
		}
		finally {
			session.destroy();
			server.stop(0);
		}
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
