package io.xyz.xyz_mcp_hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Fetch 端点集成测试：连接 {@code /mcp/builtin/fetch}，验证 listTools 暴露 {@code fetch}
 * 工具、SSRF 拦截真实生效（本地 URL 被拒并返回友好文本）。真实公网抓取见
 * {@link FetchRealApiSmoke}（手工 main 冒烟，@requires-web）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpFetchEndpointTest {

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully();
		}
	}

	private McpSyncClient connect() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint("/mcp/builtin/fetch")
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

	@Test
	void listToolsExposesFetch() {
		client = connect();
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).contains("fetch");
	}

	@Test
	void callFetchOnPrivateIpBlockedBySsrf() {
		client = connect();
		String out = callText(McpSchema.CallToolRequest.builder("fetch")
			.arguments(Map.of("url", "http://127.0.0.1:1/")).build());
		assertThat(out).contains("SSRF 防护拦截");
	}

	@Test
	void callFetchWithBlankUrlReturnsHint() {
		client = connect();
		String out = callText(McpSchema.CallToolRequest.builder("fetch")
			.arguments(Map.of("url", " ")).build());
		assertThat(out).contains("请提供要抓取的 URL");
	}
}
