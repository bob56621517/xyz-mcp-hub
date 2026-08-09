package io.xyz.xyz_mcp_hub;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubFullMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubReadonlyMcpProvider;

/**
 * GitHub 远程 MCP 冒烟（手工运行，非自动测试）。
 *
 * <p>依赖真实外部网络与 GitHub PAT：直连 GitHub 官方远程托管 MCP 端点
 * {@code https://api.githubcopilot.com/mcp/}，打印上游工具列表并校验固定只读清单命中率。
 * 详见 {@code docs/testing/mcp-service-test-guide.md}。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.GithubRealApiSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web 需真实外部网络（api.githubcopilot.com）
 * @requires-token GITHUB_TOKEN 从环境变量读取；未设置则退出
 */
public class GithubRealApiSmoke {

	private static final String UPSTREAM_URL = "https://api.githubcopilot.com/mcp/";

	public static void main(String[] args) {
		String token = System.getenv("GITHUB_TOKEN");
		System.out.println("[1/3] 依赖检查：GITHUB_TOKEN "
				+ (token == null || token.isBlank() ? "未设置，退出" : "已设置"));
		if (token == null || token.isBlank()) {
			return;
		}

		McpSyncClient client = null;
		List<String> names;
		try {
			System.out.println("[2/3] 连接 " + UPSTREAM_URL + " 并 listTools");
			client = new GithubFullMcpProvider(UPSTREAM_URL, token).connect();
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

		System.out.println("[3/3] 校验固定只读清单命中率");
		var readonly = new GithubReadonlyMcpProvider(UPSTREAM_URL, token).getToolNames();
		long hit = readonly.stream().filter(names::contains).count();
		readonly.forEach(name -> System.out.println("      " + name + (names.contains(name) ? " ✓" : " ✗")));

		System.out.println("结论：" + (hit > 0
				? "通过（命中 " + hit + "/" + readonly.size() + "）"
				: "未通过（只读清单无命中，见上方输出）"));
	}

}
