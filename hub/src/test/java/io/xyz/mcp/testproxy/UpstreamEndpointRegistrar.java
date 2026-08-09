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
 * 集成测试专用：上游 MCP Server 的端点注册器，在独立 context 中暴露已知工具列表的
 * {@code /mcp/server/upstream} 端点，供 Proxy 转发测试验证 listTools 透传与 callTool 转发。
 */
@Configuration(proxyBeanMethods = false)
public class UpstreamEndpointRegistrar {

	@Bean
	WebMvcStreamableServerTransportProvider upstreamTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
		return WebMvcStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.mcpEndpoint("/mcp/server/upstream")
			.build();
	}

	@Bean
	McpSyncServer upstreamServer(WebMvcStreamableServerTransportProvider transport) {
		return McpServer.sync(transport)
			.serverInfo(new McpSchema.Implementation("upstream", "1.0.0"))
			.tools(echoTool(), failTool())
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.immediateExecution(true)
			.build();
	}

	@Bean
	RouterFunction<ServerResponse> upstreamRouter(WebMvcStreamableServerTransportProvider transport) {
		return transport.getRouterFunction();
	}

	private McpServerFeatures.SyncToolSpecification echoTool() {
		var schema = McpSchema.JsonSchema.builder()
			.type("object")
			.properties(Map.of("message", Map.of("type", "string")))
			.required(List.of("message"))
			.additionalProperties(false)
			.build();
		var tool = McpSchema.Tool.builder()
			.name("echo")
			.description("回显消息")
			.inputSchema(schema)
			.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			String message = (String) request.arguments().get("message");
			return McpSchema.CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent("echo: " + message)))
				.build();
		});
	}

	private McpServerFeatures.SyncToolSpecification failTool() {
		var schema = McpSchema.JsonSchema.builder().type("object").additionalProperties(false).build();
		var tool = McpSchema.Tool.builder()
			.name("fail")
			.description("总是返回错误的测试工具")
			.inputSchema(schema)
			.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
			McpSchema.CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent("上游模拟失败")))
				.isError(true)
				.build());
	}

}
