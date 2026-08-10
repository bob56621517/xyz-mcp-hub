package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.function.ServerRequest;

import reactor.core.publisher.Mono;

/**
 * 单 McpServer（ADR-0011）：承载源注册表（{@link McpSourceRegistry}），为双传输（Streamable HTTP 与
 * 遗留 HTTP+SSE）提供共享的会话工厂、请求处理器与 URL 参数工具视图解析。
 *
 * <p>工具永远注册在源里；{@code tools/list} 按连接 URL 参数（经 {@link McpTransportContext} 每请求
 * 携带）过滤返回子集，被过滤的工具对 agent「不存在」。双传输共享同一套注册与过滤逻辑——过滤是应用
 * 层，与传输无关。</p>
 *
 * <p>会话工厂差异：Streamable 用 {@link DefaultMcpStreamableServerSessionFactory}（URL 参数在每次
 * POST 上都携带，提取器直接读查询参数）；SSE 的 URL 参数只在建连 GET 上出现，消息 POST 不携带，
 * 故用路由过滤器在建连时捕获参数、按会话暂存，提取器经 {@code sessionId} 取回。</p>
 */
public class McpSingleServer {

	private static final Logger log = LoggerFactory.getLogger(McpSingleServer.class);

	/** {@link McpTransportContext} 中存放当前请求 {@link ToolFilter} 的键。 */
	public static final String TOOL_FILTER_KEY = "xyz.hub.toolFilter";

	private static final String SERVER_VERSION = "1.0.0";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private final McpSourceRegistry registry;
	private final McpJsonMapper jsonMapper;
	private final McpSchema.Implementation serverInfo;
	private final McpSchema.ServerCapabilities capabilities;
	private final List<String> supportedProtocolVersions;

	/** SSE 建连 GET 捕获的过滤器，按会话暂存（消息 POST 经 sessionId 取回）。 */
	private final ConcurrentHashMap<String, ToolFilter> sseSessionFilters = new ConcurrentHashMap<>();

	/** SSE 建连 GET → 会话创建的线程桥（同一 servlet 线程同步执行）。 */
	private final ThreadLocal<ToolFilter> pendingSseFilter = new ThreadLocal<>();

	public McpSingleServer(McpSourceRegistry registry, McpJsonMapper jsonMapper) {
		this.registry = registry;
		this.jsonMapper = jsonMapper;
		this.serverInfo = new McpSchema.Implementation("xyz-mcp-hub", SERVER_VERSION);
		this.capabilities = McpSchema.ServerCapabilities.builder().tools(true).build();
		this.supportedProtocolVersions = List.of(ProtocolVersions.MCP_2024_11_05,
				ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);
	}

	/**
	 * 共享的传输上下文提取器：优先读请求查询参数 {@code includes}/{@code excludes}；SSE 消息 POST
	 * 不带参数时，经 {@code sessionId} 从会话暂存区取回建连时捕获的过滤器。
	 */
	public McpTransportContextExtractor<ServerRequest> contextExtractor() {
		return request -> {
			ToolFilter filter = ToolFilter.parse(request.param("includes"), request.param("excludes"));
			if (filter.isEmpty()) {
				String sessionId = request.param("sessionId").orElse(null);
				if (sessionId != null) {
					ToolFilter stored = sseSessionFilters.get(sessionId);
					if (stored != null) {
						filter = stored;
					}
				}
			}
			return McpTransportContext.create(Map.of(TOOL_FILTER_KEY, filter));
		};
	}

	/**
	 * SSE 建连路由过滤器调用：捕获 GET 请求的 URL 参数为「待定会话过滤器」，供
	 * {@link #sseSessionFactory()} 建会话时按会话暂存。
	 */
	public void capturePendingSseFilter(Optional<String> includes, Optional<String> excludes) {
		ToolFilter filter = ToolFilter.parse(includes, excludes);
		if (!filter.isEmpty()) {
			pendingSseFilter.set(filter);
		}
	}

	/** Streamable HTTP 会话工厂：URL 参数每请求携带，无需会话暂存。 */
	public McpStreamableServerSession.Factory streamableSessionFactory() {
		return new DefaultMcpStreamableServerSessionFactory(REQUEST_TIMEOUT, this::handleInitialize,
				requestHandlers(), notificationHandlers(), sessionId -> Mono.empty());
	}

	/** 遗留 HTTP+SSE 会话工厂：建连时经线程桥捕获过滤器、按会话暂存，会话关闭时清理。 */
	public McpServerSession.Factory sseSessionFactory() {
		return sessionTransport -> {
			ToolFilter pending = pendingSseFilter.get();
			pendingSseFilter.remove();
			String sessionId = UUID.randomUUID().toString();
			if (pending != null && !pending.isEmpty()) {
				sseSessionFilters.put(sessionId, pending);
			}
			return new McpServerSession(sessionId, REQUEST_TIMEOUT, sessionTransport, this::handleInitialize,
					requestHandlers(), notificationHandlers(),
					() -> {
						sseSessionFilters.remove(sessionId);
						return Mono.empty();
					});
		};
	}

	/** 释放会话暂存区与源注册表资源（#35 起注册表含 proxy 源，持有上游连接，close 时一并释放）。 */
	public void close() {
		registry.close();
		sseSessionFilters.clear();
		pendingSseFilter.remove();
	}

	/** 当前源注册表（供测试 / 目录 API 等读取）。 */
	public McpSourceRegistry registry() {
		return registry;
	}

	private Mono<McpSchema.InitializeResult> handleInitialize(McpSchema.InitializeRequest request) {
		String requested = request.protocolVersion();
		String version = supportedProtocolVersions.contains(requested)
			? requested
			: supportedProtocolVersions.get(supportedProtocolVersions.size() - 1);
		if (!supportedProtocolVersions.contains(requested)) {
			log.warn("客户端请求不支持的协议版本 {}，服务端建议 {} 代替", requested, version);
		}
		return Mono.just(McpSchema.InitializeResult.builder(version, capabilities, serverInfo).build());
	}

	private Map<String, McpRequestHandler<?>> requestHandlers() {
		Map<String, McpRequestHandler<?>> handlers = new HashMap<>();
		handlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Map.of()));
		handlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
		handlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler());
		handlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, (exchange, params) -> Mono.empty());
		return handlers;
	}

	private McpRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
		return (exchange, params) -> handleToolsList(exchange, params);
	}

	private McpRequestHandler<McpSchema.CallToolResult> toolsCallRequestHandler() {
		return (exchange, params) -> handleToolsCall(exchange, params);
	}

	private Map<String, McpNotificationHandler> notificationHandlers() {
		Map<String, McpNotificationHandler> handlers = new HashMap<>();
		handlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());
		return handlers;
	}

	/** tools/list：按请求携带的过滤器返回工具视图子集。 */
	private Mono<McpSchema.ListToolsResult> handleToolsList(McpAsyncServerExchange exchange, Object params) {
		ToolFilter filter = toolFilterFrom(exchange);
		List<McpSchema.Tool> tools = registry.visibleToolNames(filter).stream()
			.sorted()
			.map(registry::specByName)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(McpServerFeatures.AsyncToolSpecification::tool)
			.toList();
		return Mono.just(McpSchema.ListToolsResult.builder(tools).build());
	}

	/** tools/call：校验工具存在且在可见视图中，委托给源内注册的 call handler。 */
	private Mono<McpSchema.CallToolResult> handleToolsCall(McpAsyncServerExchange exchange, Object params) {
		McpSchema.CallToolRequest request = jsonMapper.convertValue(params, McpSchema.CallToolRequest.class);
		McpServerFeatures.AsyncToolSpecification spec = registry.specByName(request.name()).orElse(null);
		ToolFilter filter = toolFilterFrom(exchange);
		if (spec == null || !registry.isVisible(request.name(), filter)) {
			return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
				.message("Unknown tool: " + request.name())
				.build());
		}
		return spec.callHandler().apply(exchange, request);
	}

	private ToolFilter toolFilterFrom(McpAsyncServerExchange exchange) {
		Object value = exchange.transportContext().get(TOOL_FILTER_KEY);
		return value instanceof ToolFilter filter ? filter : ToolFilter.EMPTY;
	}

}
