package io.xyz.xyz_mcp_hub;

import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubFullMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.github.GithubReadonlyMcpProvider;

/**
 * 真实 GitHub 远程 MCP 冒烟验证（手动单次调用，不入测试套件）：
 * 读取环境变量 GITHUB_TOKEN，直连 GitHub 官方远程 MCP 端点 {@code https://api.githubcopilot.com/mcp/}，
 * 打印上游工具列表，并校验固定只读清单中的工具名是否真实存在。
 */
public class GithubRealApiSmoke {

	private static final String UPSTREAM_URL = "https://api.githubcopilot.com/mcp/";

	public static void main(String[] args) {
		String token = System.getenv("GITHUB_TOKEN");
		if (token == null || token.isBlank()) {
			System.err.println("未设置环境变量 GITHUB_TOKEN");
			return;
		}
		var client = new GithubFullMcpProvider(UPSTREAM_URL, token).connect();
		try {
			var tools = client.listTools().tools();
			var names = tools.stream().map(McpSchema.Tool::name).toList();
			System.out.println("===== 上游工具总数: " + names.size() + " =====");
			names.forEach(System.out::println);
			System.out.println();
			System.out.println("===== 只读清单命中 =====");
			var readonly = new GithubReadonlyMcpProvider(UPSTREAM_URL, token).getToolNames();
			long hit = readonly.stream().filter(names::contains).count();
			System.out.println("命中 " + hit + " / " + readonly.size());
			readonly.forEach(name -> System.out.println(name + (names.contains(name) ? " ✓" : " ✗")));
		}
		finally {
			client.closeGracefully();
		}
	}

}
