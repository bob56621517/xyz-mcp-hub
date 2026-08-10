package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 单端点注册器（ADR-0011，expand 阶段与旧多端点并存）：挂一个 McpServer 于双传输
 * {@code /xyz-hub/mcp}（Streamable HTTP）与 {@code /xyz-hub/sse}（HTTP+SSE），共享同一
 * {@link McpSourceRegistry} 与 URL 参数过滤逻辑。
 *
 * <p>双传输共享 {@link McpSingleServer} 提供的会话工厂与请求处理器；SSE 建连 GET 经路由过滤器
 * 捕获 URL 参数、按会话暂存，使消息 POST 也能解析同一工具视图（过滤行为与 /mcp 一致）。</p>
 *
 * <p>本注册器只新增 `/xyz-hub/*` 两个路由，不动旧多端点（旧端点由 {@code HubMcpRegistrar} 继续
 * 注册）；Spring 的 {@code RouterFunctionMapping} 会把多个 RouterFunction bean 合并。</p>
 */
@Configuration(proxyBeanMethods = false)
public class McpSingleEndpointRegistrar implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(McpSingleEndpointRegistrar.class);

	/** Streamable HTTP 端点路径。 */
	public static final String MCP_PATH = "/xyz-hub/mcp";

	/** 遗留 HTTP+SSE 端点路径。 */
	public static final String SSE_PATH = "/xyz-hub/sse";

	/** 阶段取最高：先于 WebServer（phase 0）关闭 MCP 会话，避免 Tomcat 优雅关闭空等活动 SSE 连接。 */
	private static final int PHASE = Integer.MAX_VALUE;

	private final McpSingleServer server;
	private final WebMvcStreamableServerTransportProvider streamableTransport;
	private final WebMvcSseServerTransportProvider sseTransport;

	private volatile boolean running = false;

	public McpSingleEndpointRegistrar(List<McpEndpointProvider> providers,
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			CompositeSourceProperties compositeSourceProperties) {
		McpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(jsonMapper);
		// #33 组合源：mcp.specs 配置随普通源一并进注册表，发布为目录中的 composite 源
		this.server = new McpSingleServer(
				new McpSourceRegistry(providers, compositeSourceProperties.getSpecs()), mcpJsonMapper);
		this.streamableTransport = WebMvcStreamableServerTransportProvider.builder()
			.jsonMapper(mcpJsonMapper)
			.mcpEndpoint(MCP_PATH)
			.contextExtractor(server.contextExtractor())
			.build();
		this.sseTransport = WebMvcSseServerTransportProvider.builder()
			.jsonMapper(mcpJsonMapper)
			.sseEndpoint(SSE_PATH)
			.messageEndpoint(SSE_PATH)
			.contextExtractor(server.contextExtractor())
			.build();
		this.streamableTransport.setSessionFactory(server.streamableSessionFactory());
		this.sseTransport.setSessionFactory(server.sseSessionFactory());
		log.info("单端点已挂载：{}（Streamable HTTP）与 {}（HTTP+SSE），源注册表共 {} 个源、{} 个工具",
				MCP_PATH, SSE_PATH, server.registry().sources().size(), server.registry().allToolNames().size());
	}

	/**
	 * 单端点的 RouterFunction：合并双传输路由；SSE 建连 GET 时捕获 URL 参数（供会话暂存）。
	 */
	@Bean
	RouterFunction<ServerResponse> xyzHubMcpRouterFunction() {
		RouterFunction<ServerResponse> sse = sseTransport.getRouterFunction()
			.filter((request, handler) -> {
				if (HttpMethod.GET.equals(request.method()) && SSE_PATH.equals(request.path())) {
					server.capturePendingSseFilter(request.param("includes"), request.param("excludes"));
				}
				return handler.handle(request);
			});
		return streamableTransport.getRouterFunction().and(sse);
	}

	/** 当前单 McpServer（供测试 / 目录 API 等读取）。 */
	public McpSingleServer server() {
		return server;
	}

	/**
	 * 源注册表 Bean（issue #34 目录 API 数据源）：供 {@link CatalogEndpoint} 读取已注册源。
	 * 注册表在构造时已从 provider 装配完成，本方法只把既有实例发布为可注入 Bean。
	 */
	@Bean
	McpSourceRegistry mcpSourceRegistry() {
		return server.registry();
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		// SmartLifecycle 关闭顺序：高 phase 先停——本注册器先于 WebServer（phase 0）关闭 MCP
		// 双传输会话，再交由 Tomcat 停机，避免 30s 优雅关闭空等
		streamableTransport.closeGracefully().block();
		sseTransport.closeGracefully().block();
		server.close();
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

}
