package io.xyz.xyz_mcp_hub.mcp.internal;

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 多端点注册器：收集所有 {@link McpEndpointProvider}，为每个提供者创建独立的
 * {@link McpSyncServer} + 传输层，并通过组合 RouterFunction 暴露为独立的 URL 路径。
 *
 * <p>绕过 Spring AI 的单端点自动配置（已在 {@code application.yaml} 中排除），手工管理
 * McpServer 的创建与生命周期。参考 Spring AI 自身的
 * {@code McpServerStreamableHttpWebMvcAutoConfiguration} 实现，将单实例逻辑改为循环。</p>
 */
@Configuration(proxyBeanMethods = false)
public class HubMcpRegistrar implements DisposableBean {

	private static final String SERVER_VERSION = "1.0.0";

	private final List<McpEndpointProvider> providers;
	private final JsonMapper jsonMapper;

	/** 已创建的服务，用于关闭时优雅释放资源。 */
	private final List<McpSyncServer> servers = new ArrayList<>();

	public HubMcpRegistrar(List<McpEndpointProvider> providers,
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
		this.providers = providers;
		this.jsonMapper = jsonMapper;
	}

	@Bean
	List<WebMvcStreamableServerTransportProvider> mcpServerTransports() {
		return providers.stream().map(this::registerEndpoint).toList();
	}

	private WebMvcStreamableServerTransportProvider registerEndpoint(McpEndpointProvider provider) {
		var transport = WebMvcStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.mcpEndpoint(provider.getPath())
			.build();
		var server = McpServer.sync(transport)
			.serverInfo(new McpSchema.Implementation(provider.getName(), SERVER_VERSION))
			.tools(McpToolUtils.toSyncToolSpecification(provider.getTools()))
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.immediateExecution(true)
			.build();
		servers.add(server);
		return transport;
	}

	@Bean
	RouterFunction<ServerResponse> mcpHubRouterFunction(List<WebMvcStreamableServerTransportProvider> transports) {
		return transports.stream()
			.map(WebMvcStreamableServerTransportProvider::getRouterFunction)
			.reduce(RouterFunction::and)
			.orElse(null);
	}

	@Override
	public void destroy() {
		servers.forEach(McpSyncServer::closeGracefully);
	}

}
