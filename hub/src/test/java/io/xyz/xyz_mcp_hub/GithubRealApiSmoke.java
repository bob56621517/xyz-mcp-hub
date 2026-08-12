package io.xyz.xyz_mcp_hub;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ConfigProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxySourceConfig;

/**
 * GitHub 远程 MCP 冒烟（手工运行，非自动测试）。
 *
 * <p>依赖真实外部网络与 GitHub PAT：直连 GitHub 官方远程托管 MCP 端点
 * {@code https://api.githubcopilot.com/mcp/}，打印上游工具列表。（#49 github-readonly 组合源
 * 移除后不再校验只读清单命中率；#52 起经配置驱动的 {@link ConfigProxyMcpProvider} 建源，消灭
 * 具体 Provider 类。）
 * 详见 {@code docs/testing/mcp-service-test-guide.md}。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.GithubRealApiSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web 需真实外部网络（api.githubcopilot.com）
 * @requires-token GITHUB_AUTH_HEADER 完整认证 header（如 "Authorization: Bearer <token>"）凭据来源与 Spring 运行时一致（env 优先，application-local.yml 兜底，见 {@link SmokeCredentials}）；未设置则退出
 */
public class GithubRealApiSmoke {

	private static final String UPSTREAM_URL = "https://api.githubcopilot.com/mcp/";

	public static void main(String[] args) {
		// #52 配置驱动：与 mcp.proxies auth-header 同一语义（完整 header 行，见 GITHUB_AUTH_HEADER）
		String authHeader = SmokeCredentials.get("GITHUB_AUTH_HEADER");
		System.out.println("[1/2] 依赖检查：GITHUB_AUTH_HEADER "
				+ (authHeader == null || authHeader.isBlank() ? "未设置，退出" : "已设置"));
		if (authHeader == null || authHeader.isBlank()) {
			return;
		}

		McpSyncClient client = null;
		List<String> names;
		try {
			System.out.println("[2/2] 连接 " + UPSTREAM_URL + " 并 listTools");
			ProxySourceConfig config = new ProxySourceConfig("github", UPSTREAM_URL, authHeader, null, true);
			client = new ConfigProxyMcpProvider(config).connect();
			names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
		}
		catch (RuntimeException e) {
			System.out.println("      失败：" + e.getMessage());
			System.out.println("结论：未通过（连接或 listTools 失败，见上方输出）");
			return;
		}
		finally {
			if (client != null) {
				client.closeGracefully();
			}
		}
		System.out.println("      上游工具总数：" + names.size());
		names.forEach(name -> System.out.println("      " + name));

		System.out.println("结论：通过（上游 listTools 成功，工具 " + names.size() + " 个）");
	}

}
