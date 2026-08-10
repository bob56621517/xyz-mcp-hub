package io.xyz.xyz_mcp_hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.MarkitdownUpstreamApplication;
import io.xyz.mcp.testproxy.MarkitdownUpstreamRegistrar;
import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.mcp.internal.containermcp.ContainerEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ContainerMcp markitdown 源端点集成测试（#37，主 seam）：经真实 {@code /xyz-hub/mcp} 端点验证
 * {@code includes=markitdown} 暴露 {@code markitdown_convert_to_markdown}、工具调用转发到容器内 MCP
 * 端点、SSRF 预检拦截内网地址。（旧多端点路径 404 契约由 {@code McpOldEndpointsRemovedTest} 覆盖。）
 *
 * <p>不启真实 docker：{@code docker.enabled=false} 关掉真实容器运行时，@TestConfiguration 注入 fake
 * {@link ContainerManager}（返回假句柄）与指向内嵌模拟上游的 {@link ContainerEndpoint}。内嵌上游
 * {@code MarkitdownUpstreamApplication} 暴露 {@code convert_to_markdown}（返回固定 Markdown、记录 uri）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
@Import(MarkitdownContainerMcpEndpointTest.ContainerTestConfig.class)
class MarkitdownContainerMcpEndpointTest {

	private static ConfigurableApplicationContext upstreamContext;
	private static String upstreamUrl;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@Autowired
	private ContainerManager fakeManager;

	@DynamicPropertySource
	static void registerContainerSource(DynamicPropertyRegistry registry) throws IOException {
		Path manifest = Files.createTempFile("mcp-images", ".yaml");
		Files.writeString(manifest, """
			images:
			  markitdown:
			    image: xyz-mcp-hub/markitdown:latest
			    protocol: mcp
			    port: 3001
			    hostPort: 13001
			""", StandardCharsets.UTF_8);
		upstreamContext = new SpringApplicationBuilder(MarkitdownUpstreamApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		upstreamUrl = "http://localhost:" + upstreamPort + "/mcp/server/markitdown";
		registry.add("docker.enabled", () -> "false");
		registry.add("docker.manifest-path", () -> manifest.toString());
	}

	@AfterAll
	static void stopUpstream() {
		if (upstreamContext != null) {
			upstreamContext.close();
		}
	}

	@BeforeEach
	void resetLastUri() {
		MarkitdownUpstreamRegistrar.LAST_URI.set(null);
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

	private List<String> toolNames() {
		return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
	}

	// ---- 验收 1：includes=markitdown 暴露 convert_to_markdown（静态冒烟清单） ----

	@Test
	void includesMarkitdownExposesOnlyConvertTool() {
		client = connect("/xyz-hub/mcp?includes=[markitdown]");
		assertThat(toolNames()).containsExactly("markitdown_convert_to_markdown");
	}

	@Test
	void noParamsIncludesMarkitdownInFullSet() {
		client = connect("/xyz-hub/mcp");
		assertThat(toolNames()).contains("markitdown_convert_to_markdown");
	}

	// ---- 验收 2：工具调用转发到容器内 MCP 端点（fake manager 首用拉起 + 内嵌上游） ----

	@Test
	void convertToolCallForwardsToContainerUpstream() {
		client = connect("/xyz-hub/mcp?includes=[markitdown]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("markitdown_convert_to_markdown")
			.arguments(Map.of("uri", "data:text/plain,hello"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		// @Tool 返回 String 会被 JSON 编码（全库既有行为，fetch 同），用 contains 断言
		assertThat(text.text()).contains("mock 转换结果");
		assertThat(MarkitdownUpstreamRegistrar.LAST_URI.get()).isEqualTo("data:text/plain,hello");
		// 首用拉起：fake manager.ensureRunning 被调用（验证装配收到容器管理器）
		org.mockito.Mockito.verify(fakeManager, org.mockito.Mockito.atLeastOnce())
			.ensureRunning(any(ContainerSpec.class));
	}

	@Test
	void ssrfPrecheckRejectsPrivateUrlThroughEndpoint() {
		client = connect("/xyz-hub/mcp?includes=[markitdown]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("markitdown_convert_to_markdown")
			.arguments(Map.of("uri", "http://127.0.0.1:8080/internal"))
			.build());
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).contains("SSRF 防护拦截");
		assertThat(MarkitdownUpstreamRegistrar.LAST_URI.get()).isNull();
	}

	/**
	 * 仅本测试 context：注入 fake 容器管理器（返回假句柄，不启 docker）与指向内嵌上游的端点解析。
	 */
	@Configuration(proxyBeanMethods = false)
	static class ContainerTestConfig {

		@Bean
		@Primary
		ContainerManager fakeContainerManager() {
			ContainerManager manager = mock(ContainerManager.class);
			when(manager.ensureRunning(any(ContainerSpec.class)))
				.thenAnswer(invocation -> new ContainerHandle(invocation.getArgument(0), "cid-fake"));
			return manager;
		}

		@Bean
		@Primary
		ContainerEndpoint testContainerEndpoint() {
			return spec -> upstreamUrl;
		}
	}
}
