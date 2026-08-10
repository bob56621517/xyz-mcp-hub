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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright 会话 TTL 自动回收测试：用小 TTL（1s）与扫描周期（1s）验证闲置会话被后台
 * 扫描回收。并发上限测试见 {@link McpPlaywrightSessionMaxTest}（需大 TTL，独立 context）。
 *
 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"playwright.session.ttl-seconds=1",
		"playwright.session.scan-interval-seconds=1",
		"playwright.session.max=8"
})
class McpPlaywrightSessionTtlTest {

	private static final String PAGE_HTML = """
		<!DOCTYPE html>
		<html>
		<head><meta charset="utf-8"><title>Playwright Test Page</title></head>
		<body><h1>Playwright Test Page</h1></body>
		</html>
		""";

	@LocalServerPort
	private int port;

	private HttpServer localServer;
	private McpSyncClient client;

	@BeforeEach
	void startLocalPage() throws IOException {
		localServer = HttpServer.create(new InetSocketAddress(0), 0);
		localServer.createContext("/", exchange -> respond(exchange, PAGE_HTML));
		localServer.start();
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

	private void connect() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint("/xyz-hub/mcp?includes=[playwright]")
			.build();
		var c = McpClient.sync(transport).build();
		c.initialize();
		this.client = c;
	}

	private String callText(String tool, Map<String, Object> arguments) {
		var result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
		assertThat(result.isError()).as("callTool 不应报错：%s", result).isFalse();
		assertThat(result.content()).isNotEmpty();
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	private String createSession() {
		String text = callText("playwright_web_session", Map.of("action", "create"));
		// Spring AI 对工具返回值做 JSON 序列化，文本两侧带引号，提取 sessionId 时去掉
		return text.substring(text.indexOf("sessionId: ") + "sessionId: ".length()).replace("\"", "");
	}

	@Test
	void autoReclaimsIdleSessionAfterTtl() throws Exception {
		connect();
		String sid = createSession();
		// 超过 ttl(1s) 与一个扫描周期(1s)，后台扫描应已回收
		Thread.sleep(3500);
		String result = callText("playwright_browser_snapshot", Map.of("sessionId", sid));
		assertThat(result).contains("不存在或已被关闭");
		assertThat(callText("playwright_web_session", Map.of("action", "list"))).contains("0");
	}

	@Test
	void recentActivityKeepsSessionAlive() throws Exception {
		connect();
		String sid = createSession();
		// 持续操作刷新 lastAccess，会话不应被回收
		for (int i = 0; i < 3; i++) {
			Thread.sleep(400);
			callText("playwright_browser_snapshot", Map.of("sessionId", sid));
		}
		assertThat(callText("playwright_web_session", Map.of("action", "list"))).contains("1");
		callText("playwright_web_session", Map.of("action", "close", "sessionId", sid));
	}
}
