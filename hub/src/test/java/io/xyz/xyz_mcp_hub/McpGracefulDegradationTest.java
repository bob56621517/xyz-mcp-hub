package io.xyz.xyz_mcp_hub;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 优雅降级集成测试：proxy 上游连接失败时跳过该端点、应用照常启动（ADR-0005）。
 *
 * <p>注册一个指向不可达地址（localhost:1，必然 Connection refused）的 proxy 提供者，
 * 验证：应用能启动、其它已注册端点仍可用、该端点未被暴露。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
@Import(McpGracefulDegradationTest.GracefulDegradationTestConfig.class)
class McpGracefulDegradationTest {

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@DynamicPropertySource
	static void unreachableUpstream(DynamicPropertyRegistry registry) {
		registry.add("proxy.unreachable.upstream-url", () -> "http://localhost:1/mcp/server/upstream");
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
	void appStartsAndRegisteredEndpointStillWorksWhenUpstreamUnreachable() {
		client = connect("/mcp/builtin/utils");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name).contains("currentDateTime");
	}

	@Test
	void unreachableProxyEndpointIsNotExposed() {
		assertThatThrownBy(() -> {
			var c = connect("/mcp/builtin/unreachable-proxy");
			c.listTools();
		}).isInstanceOf(Exception.class);
	}

	/**
	 * 测试专用 ProxyMcpProvider：指向配置的不可达上游。
	 */
	static class UnreachableProxyMcpProvider extends ProxyMcpProvider {

		private final String upstreamUrl;

		UnreachableProxyMcpProvider(String upstreamUrl) {
			this.upstreamUrl = upstreamUrl;
		}

		@Override
		public String getName() {
			return "unreachable-proxy";
		}

		@Override
		public String getPath() {
			return "/mcp/builtin/unreachable-proxy";
		}

		@Override
		public String getUpstreamUrl() {
			return upstreamUrl;
		}

	}

	/**
	 * 仅本测试 context 注册不可达 proxy 端点。
	 */
	@Configuration(proxyBeanMethods = false)
	static class GracefulDegradationTestConfig {

		@Bean
		UnreachableProxyMcpProvider unreachableProxyMcpProvider(
				@Value("${proxy.unreachable.upstream-url}") String upstreamUrl) {
			return new UnreachableProxyMcpProvider(upstreamUrl);
		}

	}

}
