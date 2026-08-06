package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.GithubUpstreamMcpApplication;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubFullMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubReadonlyMcpProvider;
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
 * GitHub 代理端点集成测试（mock 联通，见 {@code docs/testing/mcp-service-test-guide.md}）：
 * 内嵌 GitHub 风格上游 MCP Server（只读 get_me / search_issues + 写 create_issue + 错误 fail），
 * 经 Hub 的 {@code /mcp/server/github-full} 与 {@code /mcp/server/github-readonly} 端点验证
 * listTools 透传、isError 透传、只读清单过滤、搜索/写工具调用、Bearer 认证注入与 isEnabled 门控。
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

	@Autowired
	private GithubReadonlyMcpProvider githubReadonlyMcpProvider;

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

	private McpSchema.Tool tool(String name) {
		return McpSchema.Tool.builder()
			.name(name)
			.description("测试工具 " + name)
			.inputSchema(McpSchema.JsonSchema.builder().type("object").additionalProperties(false).build())
			.build();
	}

	@Test
	void fullProviderExposesAllUpstreamTools() {
		client = connect("/mcp/server/github-full");
		var tools = client.listTools().tools();
		assertThat(tools).extracting(McpSchema.Tool::name)
			.containsExactlyInAnyOrder("get_me", "search_issues", "create_issue", "fail");
	}

	@Test
	void fullProviderCanCallWriteTool() {
		client = connect("/mcp/server/github-full");
		assertThat(callText(client, "create_issue", Map.of("title", "bug"))).isEqualTo("created issue #123");
	}

	@Test
	void fullProviderPropagatesUpstreamError() {
		client = connect("/mcp/server/github-full");
		var result = client.callTool(McpSchema.CallToolRequest.builder("fail").arguments(Map.of()).build());
		assertThat(result.isError()).isTrue();
		var text = (McpSchema.TextContent) result.content().get(0);
		assertThat(text.text()).isEqualTo("上游模拟失败");
	}

	@Test
	void readonlyProviderHasFixedReadonlyToolList() {
		assertThat(githubReadonlyMcpProvider.getToolNames())
			.contains("get_me", "get_file_contents", "list_issues", "list_pull_requests", "search_code")
			.doesNotContain("create_issue", "create_or_update_file", "create_pull_request", "update_issue");
	}

	@Test
	void readonlySelectToolsKeepsOnlyReadonlyTools() {
		var selected = githubReadonlyMcpProvider.selectTools(List.of(tool("get_me"), tool("create_issue")));
		assertThat(selected).extracting(McpSchema.Tool::name).containsExactly("get_me");
	}

	@Test
	void readonlyProviderExposesOnlyReadonlyTools() {
		client = connect("/mcp/server/github-readonly");
		var tools = client.listTools().tools();
		// 仅暴露固定只读清单内且上游存在的工具，写工具 create_issue 被过滤
		assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("get_me", "search_issues");
	}

	@Test
	void readonlyProviderCallsSearchToolSuccessfully() {
		client = connect("/mcp/server/github-readonly");
		assertThat(callText(client, "search_issues", Map.of("query", "登录")))
			.isEqualTo("issue #1: 修复登录");
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
		assertThat(new GithubReadonlyMcpProvider("http://localhost:1/mcp", null).isEnabled()).isFalse();
		// 空 token 不产出 "Bearer null"
		assertThat(new GithubFullMcpProvider("http://localhost:1/mcp", null).getAuthHeaders()).isEmpty();
	}

}
