package io.xyz.xyz_mcp_hub;

import java.util.List;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProxyMcp 上游不可达 → 源注册但工具空集成测试（#35，#50 注册/启用分离）。
 *
 * <p>三个公共 proxy 源（context7 / grep-app / wikidata）全部指向不可达地址（localhost:1，必然
 * Connection refused），验证启动时 {@code listTools} 发现失败时：应用照常启动、单端点
 * {@code /xyz-hub/mcp} 仍可用、proxy 源工具不在工具视图内（源仍已注册但工具为空，见 ADR-0005
 * 二次修订）、原生源（utils）不受影响。</p>
 *
 * <p>无外部依赖：不可达地址连接即拒，不触网、无需真实 key。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
class McpProxyDegradationTest {

	private static final String UNREACHABLE = "http://localhost:1/mcp/server/upstream";

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@DynamicPropertySource
	static void unreachableUpstreams(DynamicPropertyRegistry registry) {
		registry.add("proxy.context7.upstream-url", () -> UNREACHABLE);
		registry.add("proxy.grep-app.upstream-url", () -> UNREACHABLE);
		registry.add("proxy.wikidata.upstream-url", () -> UNREACHABLE);
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

	@Test
	void appStartsAndNativeSourceStillWorksWhenAllProxiesUnreachable() {
		client = connect("/xyz-hub/mcp");
		List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
		assertThat(names).contains("utils_currentDateTime");
		// 降级的 proxy 源工具不在任何工具视图内
		assertThat(names).doesNotContain("context7_echo", "grep_app_echo", "wikidata_echo");
	}

	@Test
	void degradedProxySourceCannotBeSelected() {
		// 上游不可达的 proxy 源已注册但工具为空：includes=[context7] → 空工具集（源存在但无工具 + warn，连接不失败）
		client = connect("/xyz-hub/mcp?includes=[context7]");
		assertThat(client.listTools().tools()).isEmpty();
	}

}
