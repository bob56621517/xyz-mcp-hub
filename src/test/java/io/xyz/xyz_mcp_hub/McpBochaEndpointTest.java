package io.xyz.xyz_mcp_hub;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bocha 端点集成测试：验证 {@code /mcp/server/bocha} 作为独立端点存在，且当前（桩阶段）工具列表为空。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpBochaEndpointTest {

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
			.endpoint("/mcp/server/bocha")
			.build();
		var client = McpClient.sync(transport).build();
		client.initialize();
		return client;
	}

	@Test
	void listToolsIsEmptyForStubEndpoint() {
		client = connect();
		var tools = client.listTools().tools();
		assertThat(tools).isEmpty();
	}

}
