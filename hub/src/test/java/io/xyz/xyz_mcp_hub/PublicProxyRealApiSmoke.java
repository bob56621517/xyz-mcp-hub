package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ConfigProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxySourceConfig;

/**
 * 公共 Proxy 真实上游冒烟（手工运行，非自动测试）。
 *
 * <p>直接连接三个免认证公共 MCP Server 真实上游，验证 listTools 连通，并调用 context7
 * 代表性工具 resolve-library-id。模板与约定见 {@code docs/testing/mcp-service-test-guide.md}。</p>
 *
 * <p>每步带超时保护（{@value #STEP_TIMEOUT_SECONDS}s）：公共服务响应偶发不稳定，
 * 单步超时不阻塞整体，该步判为未通过并在结论中说明。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.PublicProxyRealApiSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web 需真实外部网络（mcp.context7.com / mcp.grep.app / wd-mcp.wmcloud.org）
 */
public class PublicProxyRealApiSmoke {

	private static final long STEP_TIMEOUT_SECONDS = 25;

	/** context7 官方 Streamable HTTP 端点（默认，与 application.yaml 一致）。 */
	private static final String CONTEXT7_UPSTREAM_URL = "https://mcp.context7.com/mcp";

	/**
	 * grep.app 官方 Streamable HTTP 端点（默认，与 application.yaml 一致）。
	 *
	 * <p>注意尾斜杠：grep.app 真实 MCP 端点位于根路径，{@code /mcp} 返回 {@code Invalid MCP endpoint}；
	 * {@link ProxyMcpProvider#connect()} 把空路径默认拼为 {@code /mcp}，故此处带尾斜杠使
	 * {@code path="/"} 指向根端点。</p>
	 */
	private static final String GREPAPP_UPSTREAM_URL = "https://mcp.grep.app/";

	/** Wikidata 官方 Streamable HTTP 端点（默认，与 application.yaml 一致）。 */
	private static final String WIKIDATA_UPSTREAM_URL = "https://wd-mcp.wmcloud.org/mcp";

	public static void main(String[] args) {
		// #52 配置驱动：经 ProxySourceConfig 建源（免认证公开代理），消灭具体 Provider 类
		System.out.println("[1/4] context7 连通性：" + CONTEXT7_UPSTREAM_URL);
		boolean context7Ok = runWithTimeout("context7", PublicProxyRealApiSmoke::smokeContext7);

		System.out.println("[2/4] grep.app 连通性：" + GREPAPP_UPSTREAM_URL);
		boolean grepOk = runWithTimeout("grep-app",
				() -> smokeListTools(provider("grep-app", GREPAPP_UPSTREAM_URL)));

		System.out.println("[3/4] wikidata 连通性：" + WIKIDATA_UPSTREAM_URL);
		boolean wikiOk = runWithTimeout("wikidata",
				() -> smokeListTools(provider("wikidata", WIKIDATA_UPSTREAM_URL)));

		boolean ok = context7Ok && grepOk && wikiOk;
		System.out.println("[4/4] 结论：" + (ok ? "通过（三服务均连通，context7 调用成功）" : "未通过（见上方输出）"));
	}

	/** 免认证公开代理源（无 auth-header → 默认启用）。 */
	private static ProxyMcpProvider provider(String name, String upstreamUrl) {
		return new ConfigProxyMcpProvider(new ProxySourceConfig(name, upstreamUrl, null, null, null));
	}

	private static boolean runWithTimeout(String label, Callable<Boolean> step) {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			return pool.submit(step).get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			System.out.println("      " + label + " 步骤超时或失败：" + e.getMessage());
			return false;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static boolean smokeContext7() throws Exception {
		McpSyncClient client = null;
		try {
			client = provider("context7", CONTEXT7_UPSTREAM_URL).connect();
			List<String> tools = toolNames(client);
			System.out.println("      listTools " + tools.size() + " 个：" + truncate(String.join(", ", tools), 200));
			String text = callText(client, "resolve-library-id",
					Map.of("libraryName", "Spring Boot", "query", "Spring Boot"));
			System.out.println("      resolve-library-id(\"Spring Boot\")：\n" + truncate(text, 500));
			return !text.isBlank() && !text.contains("失败");
		}
		finally {
			if (client != null) {
				client.closeGracefully();
			}
		}
	}

	private static boolean smokeListTools(ProxyMcpProvider provider) throws Exception {
		McpSyncClient client = null;
		try {
			client = provider.connect();
			List<String> tools = toolNames(client);
			System.out.println("      listTools " + tools.size() + " 个：" + truncate(String.join(", ", tools), 200));
			return !tools.isEmpty();
		}
		finally {
			if (client != null) {
				client.closeGracefully();
			}
		}
	}

	private static List<String> toolNames(McpSyncClient client) {
		return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
	}

	private static String callText(McpSyncClient client, String toolName, Map<String, Object> arguments) {
		var result = client.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
		// isError 为 MCP 可选字段，真实上游可能不返回（null），不可直接拆箱
		if (Boolean.TRUE.equals(result.isError())) {
			return "调用失败：" + truncate(firstText(result), 200);
		}
		return firstText(result);
	}

	private static String firstText(McpSchema.CallToolResult result) {
		return result.content().stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map(c -> ((McpSchema.TextContent) c).text())
			.findFirst()
			.orElse("");
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

}
