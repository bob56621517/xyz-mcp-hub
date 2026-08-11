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
 * Playwright 会话并发上限测试：配置 {@code max=2}，验证超上限创建被拒绝、关闭后可继续。
 * 用大 TTL（300s）避免测试期间会话被自动回收干扰断言（回收测试见 {@link McpPlaywrightSessionTtlTest}）。
 *
 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"playwright.session.max=2"
})
class McpPlaywrightSessionMaxTest {

	@LocalServerPort
	private int port;

	private HttpServer localServer;
	private McpSyncClient client;

	@BeforeEach
	void startLocalPage() throws IOException {
		localServer = HttpServer.create(new InetSocketAddress(0), 0);
		localServer.createContext("/", exchange -> respond(exchange, "ok"));
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
			.endpoint("/xyz-hub/mcp?includes=[playwright*]")
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
	void enforcesMaxConcurrentSessions() {
		connect();
		String s1 = createSession();
		String s2 = createSession();
		// 达上限后创建被拒绝
		assertThat(callText("playwright_web_session", Map.of("action", "create"))).contains("已达上限");
		// 关闭一个后可继续创建
		assertThat(callText("playwright_web_session", Map.of("action", "close", "sessionId", s1))).contains("已关闭");
		String s3 = createSession();
		assertThat(s3).startsWith("ws-");
		callText("playwright_web_session", Map.of("action", "close", "sessionId", s2));
		callText("playwright_web_session", Map.of("action", "close", "sessionId", s3));
	}
}
