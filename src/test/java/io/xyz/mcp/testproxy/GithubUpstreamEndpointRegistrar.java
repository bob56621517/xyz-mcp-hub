package io.xyz.mcp.testproxy;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 集成测试专用：GitHub 风格上游 MCP Server 的端点注册器，在独立 context 中暴露
 * {@code /mcp/server/upstream} 端点，提供只读工具（get_me / search_issues）与写工具
 * （create_issue）混合列表，供 GitHub 端点测试验证全量透传与只读清单过滤。
 */
@Configuration(proxyBeanMethods = false)
public class GithubUpstreamEndpointRegistrar {

	@Bean
	WebMvcStreamableServerTransportProvider githubUpstreamTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
		return WebMvcStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.mcpEndpoint("/mcp/server/upstream")
			.build();
	}

	@Bean
	McpSyncServer githubUpstreamServer(WebMvcStreamableServerTransportProvider transport) {
		return McpServer.sync(transport)
			.serverInfo(new McpSchema.Implementation("github-upstream", "1.0.0"))
			.tools(getMeTool(), searchIssuesTool(), createIssueTool())
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.immediateExecution(true)
			.build();
	}

	@Bean
	RouterFunction<ServerResponse> githubUpstreamRouter(WebMvcStreamableServerTransportProvider transport) {
		return transport.getRouterFunction();
	}

	private McpServerFeatures.SyncToolSpecification getMeTool() {
		var tool = McpSchema.Tool.builder()
			.name("get_me")
			.description("获取当前用户")
			.inputSchema(McpSchema.JsonSchema.builder().type("object").additionalProperties(false).build())
			.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
			McpSchema.CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent("octocat")))
				.build());
	}

	private McpServerFeatures.SyncToolSpecification searchIssuesTool() {
		var schema = McpSchema.JsonSchema.builder()
			.type("object")
			.properties(Map.of("query", Map.of("type", "string")))
			.required(List.of("query"))
			.additionalProperties(false)
			.build();
		var tool = McpSchema.Tool.builder()
			.name("search_issues")
			.description("搜索议题")
			.inputSchema(schema)
			.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
			McpSchema.CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent("issue #1: 修复登录")))
				.build());
	}

	private McpServerFeatures.SyncToolSpecification createIssueTool() {
		var schema = McpSchema.JsonSchema.builder()
			.type("object")
			.properties(Map.of("title", Map.of("type", "string")))
			.required(List.of("title"))
			.additionalProperties(false)
			.build();
		var tool = McpSchema.Tool.builder()
			.name("create_issue")
			.description("创建议题")
			.inputSchema(schema)
			.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
			McpSchema.CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent("created issue #123")))
				.build());
	}

}
