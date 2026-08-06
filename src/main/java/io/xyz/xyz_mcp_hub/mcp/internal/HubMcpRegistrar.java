package io.xyz.xyz_mcp_hub.mcp.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>注册规则（见 ADR-0005）：缺必要配置的端点跳过注册（{@link McpEndpointProvider#isEnabled()}）；
 * proxy 上游连接失败时跳过该端点并记录失败日志，应用照常启动。</p>
 */
@Configuration(proxyBeanMethods = false)
public class HubMcpRegistrar implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(HubMcpRegistrar.class);

	private static final String SERVER_VERSION = "1.0.0";

	private final List<McpEndpointProvider> providers;
	private final JsonMapper jsonMapper;

	/** 已创建的服务，用于关闭时优雅释放资源。 */
	private final List<McpSyncServer> servers = new ArrayList<>();

	/** 已建立的到上游的代理连接，关闭时优雅释放。 */
	private final List<McpSyncClient> upstreamClients = new ArrayList<>();

	public HubMcpRegistrar(List<McpEndpointProvider> providers,
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
		this.providers = providers;
		this.jsonMapper = jsonMapper;
	}

	@Bean
	List<WebMvcStreamableServerTransportProvider> mcpServerTransports() {
		return providers.stream().map(this::registerEndpoint).filter(Objects::nonNull).toList();
	}

	private WebMvcStreamableServerTransportProvider registerEndpoint(McpEndpointProvider provider) {
		String name = provider.getName();
		String path = provider.getPath();
		if (!provider.isEnabled()) {
			log.info("MCP 服务 {}（{}）跳过注册：缺少必要配置", name, path);
			return null;
		}
		var transport = WebMvcStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.mcpEndpoint(provider.getPath())
			.build();
		var server = McpServer.sync(transport)
			.serverInfo(new McpSchema.Implementation(provider.getName(), SERVER_VERSION))
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.immediateExecution(true);
		if (provider instanceof ProxyMcpProvider proxy) {
			try {
				server.tools(proxyTools(proxy));
			}
			catch (RuntimeException e) {
				log.error("MCP 服务 {}（{}）注册失败，已跳过：{}", name, path, e.getMessage());
				return null;
			}
		}
		else {
			server.tools(McpToolUtils.toSyncToolSpecification(provider.getTools()));
		}
		servers.add(server.build());
		log.info("MCP 服务 {}（{}）注册成功", name, path);
		return transport;
	}

	/**
	 * 代理端点的工具：连接上游拉取工具列表（可选按提供者固定子集透传），callTool 时透明
	 * 转发、响应原样返回（含 isError）。连接由本注册器持有，{@link #destroy()} 时优雅释放。
	 */
	private List<McpServerFeatures.SyncToolSpecification> proxyTools(ProxyMcpProvider proxy) {
		McpSyncClient upstream = proxy.connect();
		upstreamClients.add(upstream);
		return proxy.selectTools(upstream.listTools().tools()).stream()
			.map(tool -> new McpServerFeatures.SyncToolSpecification(tool,
					(exchange, request) -> upstream.callTool(request)))
			.toList();
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
		upstreamClients.forEach(McpSyncClient::closeGracefully);
		// 清空使 destroy 幂等：资源已释放，重复调用为空操作
		servers.clear();
		upstreamClients.clear();
	}

}
