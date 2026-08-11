package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.GithubUpstreamMcpApplication;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubFullMcpProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * GitHub 代理源集成测试（mock 联通，见 {@code docs/testing/mcp-service-test-guide.md}；#39 迁移：
 * 旧多端点已移除，改经单端点 {@code /xyz-hub/mcp?includes=[github_full*]} 暴露，
 * 工具名带 {@code github_full_} 前缀；#49 github-readonly 组合源已移除）：
 * 内嵌 GitHub 风格上游 MCP Server（只读 get_me / search_issues + 写 create_issue + 错误 fail），
 * 验证 listTools 透传、isError 透传、搜索/写工具调用、Bearer 认证注入与 isEnabled 门控。
 *
 * <p>无外部依赖：内嵌上游模拟，不触网、不需真实 token。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = XyzMcpHubApplication.class)
class McpGithubEndpointTest {

	private static ConfigurableApplicationContext upstreamContext;

	@LocalServerPort
	private int port;

	@Autowired
	private GithubFullMcpProvider githubFullMcpProvider;

	private McpSyncClient client;

	@DynamicPropertySource
	static void githubUpstream(DynamicPropertyRegistry registry) {
		upstreamContext = new SpringApplicationBuilder(GithubUpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		registry.add("github.upstream-url",
				() -> "http://localhost:" + upstreamPort + "/mcp/server/upstream");
		// 端点按 token 非空才注册（ADR-0005），测试注入假 token 使 github 端点生效
		registry.add("github.token", () -> "test-token");
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

	private String callText(McpSyncClient c, String toolName, Map<String, Object> arguments) {
		var result = c.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
		assertThat(result.isError()).isFalse();
		assertThat(result.content()).isNotEmpty();
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	@Test
	void fullProviderExposesAllUpstreamTools() {
		client = connect("/xyz-hub/mcp?includes=[github_full*]");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name)
			.containsExactlyInAnyOrder("github_full_get_me", "github_full_search_issues",
					"github_full_create_issue", "github_full_fail");
	}

	@Test
	void fullProviderCanCallWriteTool() {
		client = connect("/xyz-hub/mcp?includes=[github_full*]");
		assertThat(callText(client, "github_full_create_issue", Map.of("title", "bug")))
			.isEqualTo("created issue #123");
	}

	@Test
	void fullProviderPropagatesUpstreamError() {
		client = connect("/xyz-hub/mcp?includes=[github_full*]");
		var result = client.callTool(McpSchema.CallToolRequest.builder("github_full_fail").arguments(Map.of()).build());
		assertThat(result.isError()).isTrue();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("上游模拟失败");
	}

	@Test
	void authHeadersInjectedFromConfiguration() {
		assertThat(githubFullMcpProvider.getAuthHeaders())
			.containsEntry("Authorization", "Bearer test-token");
	}

	@Test
	void disabledWhenTokenMissing() {
		assertThat(githubFullMcpProvider.isEnabled()).isTrue();
		assertThat(new GithubFullMcpProvider("http://localhost:1/mcp", " ").isEnabled()).isFalse();
		// 空 token 不产出 "Bearer null"
		assertThat(new GithubFullMcpProvider("http://localhost:1/mcp", null).getAuthHeaders()).isEmpty();
	}

}
