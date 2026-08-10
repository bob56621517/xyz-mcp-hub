package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import reactor.core.publisher.Mono;

/**
 * 代理 MCP 服务基类：Hub 作为 MCP Client 透明代理远程公有云 MCP Server。
 *
 * <p>仅支持远程 HTTP（Streamable HTTP）传输（见 ADR-0007），永不用 stdio 子进程。
 * 子类需提供上游完整端点 URL 与可选认证 header（敏感值经 Spring 配置注入）。旧多端点由
 * {@code HubMcpRegistrar} 调用 {@link #connect()} 拉取工具列表原样透传；#35 起单端点源注册表经
 * {@link #discoverTools()} 启动时发现工具并缓存，callTool 时透明转发、响应原样返回；关闭时经
 * {@link McpSyncClient#closeGracefully()} 释放。</p>
 */
public abstract class ProxyMcpProvider implements McpEndpointProvider {

	@Override
	public final Scope getScope() {
		return Scope.NETWORK;
	}

	/**
	 * 上游远程 MCP Server 的完整端点 URL（Streamable HTTP），如
	 * {@code https://api.example.com/mcp/server/foo}。
	 */
	public abstract String getUpstreamUrl();

	/**
	 * 可选认证 header 字段，随每个上游请求发送。默认无认证；敏感值（如 token）经
	 * Spring 配置注入（见 ADR-0005，进 application-local.yml）。
	 */
	public Map<String, String> getAuthHeaders() {
		return Map.of();
	}

	/**
	 * 工具子集钩子：返回需透传的工具名；空列表表示全量透传上游工具。需要工具子集的
	 * 子类在此代码里固定列表（ADR-0007 决策 3：不做通用过滤机制）。
	 */
	public List<String> getToolNames() {
		return List.of();
	}

	/**
	 * 从上游工具列表中按 {@link #getToolNames()} 选出待透传的工具；空子集返回全部。
	 */
	public List<McpSchema.Tool> selectTools(List<McpSchema.Tool> upstreamTools) {
		List<String> subset = getToolNames();
		if (subset.isEmpty()) {
			return upstreamTools;
		}
		return upstreamTools.stream().filter(tool -> subset.contains(tool.name())).toList();
	}

	/**
	 * 建立到上游的同步 MCP Client 连接（含 initialize 握手）。调用方负责关闭。
	 */
	public McpSyncClient connect() {
		URI uri = URI.create(getUpstreamUrl());
		String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
		String endpoint = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/mcp" : uri.getPath();
		var builder = HttpClientStreamableHttpTransport.builder(baseUri).endpoint(endpoint);
		Map<String, String> authHeaders = getAuthHeaders();
		if (!authHeaders.isEmpty()) {
			var requestBuilder = HttpRequest.newBuilder();
			authHeaders.forEach(requestBuilder::header);
			builder.requestBuilder(requestBuilder);
		}
		var client = McpClient.sync(builder.build()).build();
		client.initialize();
		return client;
	}

	/** 启动时发现持有并缓存的上游连接；由 {@link #close()} 释放。 */
	private volatile McpSyncClient discoveredClient;

	/**
	 * 启动时向上游 {@code listTools} 发现工具清单并缓存（#35，工具清单来源规则：公有云 ProxyMcp
	 * 启动时发现，见 ADR-0011）。返回的 {@link McpServerFeatures.AsyncToolSpecification} 使用上游
	 * 原始 ToolSchema，callHandler 透明转发 callTool 到上游、响应原样返回（含 isError）。
	 *
	 * <p>上游不可达（连接/握手/listTools 失败）时抛 {@link RuntimeException}，由源注册表捕获做源
	 * 降级——沿用 {@link #isEnabled()} 语义：该源不入注册表、应用照常启动。</p>
	 *
	 * <p>转发时把 Hub 侧的带前缀工具名（{@code {source}_{tool}}，如 {@code context7_query_docs}）
	 * 翻译回上游原始工具名（如 {@code query_docs}）再转发，否则上游不识别带前缀名。</p>
	 *
	 * @return 上游工具规格列表（已按 {@link #getToolNames()} 固定子集过滤）
	 */
	public List<McpServerFeatures.AsyncToolSpecification> discoverTools() {
		McpSyncClient upstream = connect();
		try {
			List<McpSchema.Tool> tools = selectTools(upstream.listTools().tools());
			List<McpServerFeatures.AsyncToolSpecification> specs = tools.stream()
				.map(tool -> new McpServerFeatures.AsyncToolSpecification(tool,
						(exchange, request) -> Mono.fromCallable(() -> upstream.callTool(
								new McpSchema.CallToolRequest(tool.name(), request.arguments(), request.meta())))))
				.toList();
			McpSyncClient previous = discoveredClient;
			if (previous != null) {
				previous.closeGracefully();
			}
			this.discoveredClient = upstream;
			return specs;
		}
		catch (RuntimeException e) {
			upstream.closeGracefully();
			throw e;
		}
	}

	/**
	 * 释放启动时发现建立的上游连接（源注册表 close 时调用）。未发现或无连接时为空操作。
	 */
	public void close() {
		McpSyncClient client = discoveredClient;
		if (client != null) {
			client.closeGracefully();
			discoveredClient = null;
		}
	}

}
