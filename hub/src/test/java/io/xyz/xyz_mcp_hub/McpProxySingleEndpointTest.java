package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.UpstreamMcpApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProxyMcp 源迁移 + 启动时发现集成测试（#35，主 seam /xyz-hub/mcp）。
 *
 * <p>内嵌上游 MCP Server（{@code UpstreamMcpApplication}，工具 echo / fail）模拟公有云，三个公共
 * proxy 源（context7 / grep-app / wikidata）经配置注入指向内嵌上游，验证启动时 {@code listTools}
 * 发现注册进单端点源注册表后的行为：</p>
 *
 * <ol>
 *   <li>{@code includes=context7} 只暴露 {@code context7_*} 工具并可调用转发（验收 1）</li>
 *   <li>启动时 listTools 发现生效，无参连接全量可见（验收 2）</li>
 *   <li>转发工具名 {@code {source}_{tool}} 全局唯一（含连字符源名 grep-app 归一化为下划线前缀），
 *       可被 {@code excludes} 精确减（验收 4）</li>
 *   <li>上游 isError 原样透传</li>
 * </ol>
 *
 * <p>无外部依赖：内嵌上游模拟，不触网、无需真实 key。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
class McpProxySingleEndpointTest {

	private static ConfigurableApplicationContext upstreamContext;

	@LocalServerPort
	private int port;

	private McpSyncClient client;

	@DynamicPropertySource
	static void embeddedUpstream(DynamicPropertyRegistry registry) {
		upstreamContext = new SpringApplicationBuilder(UpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		String upstreamUrl = "http://localhost:" + upstreamPort + "/mcp/server/upstream";
		registry.add("proxy.context7.upstream-url", () -> upstreamUrl);
		registry.add("proxy.grep-app.upstream-url", () -> upstreamUrl);
		registry.add("proxy.wikidata.upstream-url", () -> upstreamUrl);
	}

	@AfterAll
	static void stopUpstream() {
		if (upstreamContext != null) {
			upstreamContext.close();
		}
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

	@Test
	void includesSourceNameExposesOnlyThatProxySourceTools() {
		client = connect("/xyz-hub/mcp?includes=[context7]");
		assertThat(toolNames()).containsExactlyInAnyOrder("context7_echo", "context7_fail");
	}

	@Test
	void discoveredToolCanBeCalledAndForwardsToUpstream() {
		client = connect("/xyz-hub/mcp?includes=[context7]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("context7_echo")
			.arguments(Map.of("message", "你好"))
			.build());
		assertThat(result.isError()).isFalse();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("echo: 你好");
	}

	@Test
	void upstreamErrorPropagatesIsError() {
		client = connect("/xyz-hub/mcp?includes=[context7]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("context7_fail").arguments(Map.of()).build());
		assertThat(result.isError()).isTrue();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("上游模拟失败");
	}

	@Test
	void noParamsExposesAllDiscoveredProxyTools() {
		client = connect("/xyz-hub/mcp");
		List<String> names = toolNames();
		assertThat(names)
			.contains("context7_echo", "context7_fail", "grep_app_echo", "wikidata_echo", "utils_currentDateTime");
	}

	@Test
	void hyphenSourceNameNormalizesToUnderscorePrefix() {
		client = connect("/xyz-hub/mcp?includes=[grep-app]");
		assertThat(toolNames()).containsExactlyInAnyOrder("grep_app_echo", "grep_app_fail");
	}

	@Test
	void excludesRemovesExactlyOneToolFromProxySource() {
		client = connect("/xyz-hub/mcp?includes=[context7]&excludes=[context7_echo]");
		assertThat(toolNames()).containsExactly("context7_fail");
	}

}
