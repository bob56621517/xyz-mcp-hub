package io.xyz.xyz_mcp_hub;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Utils 原生源集成测试（#39 迁移：旧多端点 {@code /mcp/builtin/utils} 已移除，改经单端点
 * {@code /xyz-hub/mcp?includes=[utils*]} 暴露，工具名带 {@code utils_} 前缀）。
 *
 * <p>{@code @DirtiesContext(BEFORE_CLASS)}：本类无自定义动态属性，会复用共享 Spring context；
 * 完整套件中其他类创建第二个 context（Vaadin/Atmosphere 全局状态）会把共享 context 的单端点传输置为
 * {@code isClosing}，导致连 {@code /xyz-hub/mcp} 收到 503。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class McpUtilsEndpointTest {

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
			.endpoint("/xyz-hub/mcp?includes=[utils*]")
			.build();
		var client = McpClient.sync(transport).build();
		client.initialize();
		return client;
	}

	@Test
	void listToolsExposesCurrentDateTime() {
		client = connect();
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).contains("utils_currentDateTime");
	}

	@Test
	void callCurrentDateTimeReturnsNonEmptyText() {
		client = connect();
		var result = client.callTool(
				McpSchema.CallToolRequest.builder("utils_currentDateTime").arguments(Map.of()).build());
		assertThat(result.isError()).isFalse();
		assertThat(result.content()).isNotEmpty();
		var textContent = (McpSchema.TextContent) result.content().get(0);
		assertThat(textContent.text()).isNotBlank();
	}
}
