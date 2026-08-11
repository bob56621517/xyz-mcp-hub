package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.net.URI;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.docker.ContainerEndpoint;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContainerMcp 的 MCP 转发客户端（protocol=mcp 型，#37）：把一次 {@code tools/call} 透明转发到容器内
 * MCP 服务端点，容器结果（含 isError）原样返回。
 *
 * <p>容器生命周期（首用拉起 / 防重拉 / 闲置回收 / 关闭销毁）由 {@code docker} 模块的
 * {@code ContainerManager} 管理，本类只在调用时连接容器端点——每次调用新建 MCP 会话（initialize +
 * callTool + close），无跨调用残留、无会话失效问题；markitdown 转换本身是重操作，初始化握手开销可忽略。</p>
 *
 * <p>初始化重试（真实冒烟实测，见 #37）：ContainerManager 的健康检查是 TCP 探活，uvicorn（FastMCP）
 * 绑定端口后应用可能仍在启动（lifespan 未就绪），首个 initialize 偶发失败——对 initialize 做短重试，
 * 消除启动竞态。</p>
 */
public class ContainerMcpClient {

	private static final Logger log = LoggerFactory.getLogger(ContainerMcpClient.class);

	/** initialize 失败重试次数（含首次）。实测（#37）：容器 TCP 探活通过后 uvicorn（FastMCP）需数秒才
	 *  HTTP 就绪，窗口随系统负载波动（实测 3s～11s+）；重试窗口取 20s 覆盖之。 */
	private static final int INITIALIZE_RETRIES = 20;

	/** 重试间隔（毫秒）。 */
	private static final long RETRY_DELAY_MS = 1_000;

	private final ContainerEndpoint endpoint;
	private final ContainerManager containerManager;

	/** @param containerManager 首用拉起 + 重试期间 ensureRunning（touch）容器，防短 TTL/并发回收误删重试中的容器 */
	public ContainerMcpClient(ContainerEndpoint endpoint, ContainerManager containerManager) {
		this.endpoint = endpoint;
		this.containerManager = containerManager;
	}

	/** 向容器 MCP 端点转发一次 tools/call，返回容器原样结果（含 isError）。 */
	public McpSchema.CallToolResult call(ContainerSpec spec, McpSchema.CallToolRequest request) {
		McpSyncClient client = connect(spec);
		try {
			return client.callTool(request);
		}
		finally {
			client.closeGracefully();
		}
	}

	private McpSyncClient connect(ContainerSpec spec) {
		URI uri = URI.create(endpoint.mcpUrl(spec));
		String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
		// 默认 /mcp/（尾斜杠）：FastMCP Mount 在 /mcp，/mcp 会 307 到 /mcp/，Java client 不跟随 POST 重定向
		String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/mcp/" : uri.getPath();
		RuntimeException lastError = null;
		for (int attempt = 1; attempt <= INITIALIZE_RETRIES; attempt++) {
			// 每次重试 ensureRunning：touch 容器（防短 TTL/并发回收在重试窗口内回收容器）；若已被回收则
			// 重新拉起（幂等）。ensureRunning 对运行中容器仅 map 查找 + touch，开销可忽略。
			if (containerManager != null) {
				containerManager.ensureRunning(spec);
			}
			McpSyncClient client = McpClient.sync(
				HttpClientStreamableHttpTransport.builder(baseUri).endpoint(path).build()).build();
			try {
				client.initialize();
				return client;
			}
			catch (RuntimeException e) {
				lastError = e;
				client.closeGracefully();
				// 连接不可达（ConnectException/UnknownHost）不是启动窗口：容器未运行/端口错，重试也白费，快速失败
				if (isUnreachable(e)) {
					break;
				}
				if (attempt < INITIALIZE_RETRIES) {
					log.warn("容器 {} MCP 初始化失败（第 {}/{} 次，将重试）：{}",
						spec.name(), attempt, INITIALIZE_RETRIES, e.getMessage());
					sleep(RETRY_DELAY_MS);
				}
			}
		}
		throw new IllegalStateException("容器 MCP 端点初始化失败（" + INITIALIZE_RETRIES
			+ " 次重试后仍失败）：" + lastError.getMessage(), lastError);
	}

	/** 连接不可达（vs 启动窗口 EOF/reset）：异常链含 ConnectException / UnknownHostException 即判定不可达。 */
	private static boolean isUnreachable(RuntimeException e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
				return true;
			}
		}
		return false;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
