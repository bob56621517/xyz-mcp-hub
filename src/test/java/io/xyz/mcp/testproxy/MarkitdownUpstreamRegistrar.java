package io.xyz.mcp.testproxy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
 * 集成测试专用：markitdown 模拟上游的端点注册器，在独立 context 中暴露
 * {@code /mcp/server/markitdown} 端点与 {@code convert_to_markdown} 工具。
 */
@Configuration(proxyBeanMethods = false)
public class MarkitdownUpstreamRegistrar {

	/** 最后一次调用 {@code convert_to_markdown} 收到的 uri（供测试断言 file:// 传参）。 */
	public static final AtomicReference<String> LAST_URI = new AtomicReference<>();

	@Bean
	WebMvcStreamableServerTransportProvider markitdownUpstreamTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
		return WebMvcStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.mcpEndpoint("/mcp/server/markitdown")
			.build();
	}

	@Bean
	McpSyncServer markitdownUpstreamServer(WebMvcStreamableServerTransportProvider transport) {
		return McpServer.sync(transport)
			.serverInfo(new McpSchema.Implementation("markitdown-mock", "1.0.0"))
			.tools(convertTool())
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.immediateExecution(true)
			.build();
	}

	@Bean
	RouterFunction<ServerResponse> markitdownUpstreamRouter(WebMvcStreamableServerTransportProvider transport) {
		return transport.getRouterFunction();
	}

	private McpServerFeatures.SyncToolSpecification convertTool() {
		var schema = McpSchema.JsonSchema.builder()
			.type("object")
			.properties(Map.of("uri", Map.of("type", "string")))
			.required(List.of("uri"))
			.additionalProperties(false)
			.build();
		var tool = McpSchema.Tool.builder()
			.name("convert_to_markdown")
			.description("mock markitdown 转换")
			.inputSchema(schema)
			.build();
		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			LAST_URI.set((String) request.arguments().get("uri"));
			return McpSchema.CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent("# mock 转换结果\n\nmarkdown 正文")))
				.build();
		});
	}

}
