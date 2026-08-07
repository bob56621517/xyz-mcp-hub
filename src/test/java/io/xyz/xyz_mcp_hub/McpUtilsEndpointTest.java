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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
			.endpoint("/mcp/builtin/utils")
			.build();
		var client = McpClient.sync(transport).build();
		client.initialize();
		return client;
	}

	@Test
	void listToolsExposesCurrentDateTime() {
		client = connect();
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).contains("currentDateTime");
	}

	@Test
	void callCurrentDateTimeReturnsNonEmptyText() {
		client = connect();
		var result = client.callTool(
				McpSchema.CallToolRequest.builder("currentDateTime").arguments(Map.of()).build());
		assertThat(result.isError()).isFalse();
		assertThat(result.content()).isNotEmpty();
		var textContent = (McpSchema.TextContent) result.content().get(0);
		assertThat(textContent.text()).isNotBlank();
	}
}
