package io.xyz.xyz_mcp_hub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.context7.Context7McpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.grepapp.GrepAppMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.network.wikidata.WikidataMcpProvider;

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

	public static void main(String[] args) {
		System.out.println("[1/4] context7 连通性：" + Context7McpProvider.DEFAULT_UPSTREAM_URL);
		boolean context7Ok = runWithTimeout("context7", PublicProxyRealApiSmoke::smokeContext7);

		System.out.println("[2/4] grep.app 连通性：" + GrepAppMcpProvider.DEFAULT_UPSTREAM_URL);
		boolean grepOk = runWithTimeout("grep-app",
				() -> smokeListTools(new GrepAppMcpProvider(GrepAppMcpProvider.DEFAULT_UPSTREAM_URL)));

		System.out.println("[3/4] wikidata 连通性：" + WikidataMcpProvider.DEFAULT_UPSTREAM_URL);
		boolean wikiOk = runWithTimeout("wikidata",
				() -> smokeListTools(new WikidataMcpProvider(WikidataMcpProvider.DEFAULT_UPSTREAM_URL)));

		boolean ok = context7Ok && grepOk && wikiOk;
		System.out.println("[4/4] 结论：" + (ok ? "通过（三服务均连通，context7 调用成功）" : "未通过（见上方输出）"));
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
			client = new Context7McpProvider(Context7McpProvider.DEFAULT_UPSTREAM_URL).connect();
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
