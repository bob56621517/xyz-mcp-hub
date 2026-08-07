package io.xyz.xyz_mcp_hub.mcp.internal.proxy.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub 全量代理 MCP 端点提供者，暴露 {@code /mcp/builtin/github-full}。
 *
 * <p>透传 GitHub 官方远程托管 MCP Server（{@code https://api.githubcopilot.com/mcp/}）的全部工具，
 * 含读写操作。认证用 GitHub Personal Access Token（Bearer header），经 Spring 配置注入（ADR-0005）。
 * 缺少 {@code github.token} 时端点不注册（优雅降级）。</p>
 */
@Component
public class GithubFullMcpProvider extends AbstractGithubMcpProvider {

	public GithubFullMcpProvider(
			@Value("${github.upstream-url:https://api.githubcopilot.com/mcp/}") String upstreamUrl,
			@Value("${github.token:}") String token) {
		super(upstreamUrl, token);
	}

	@Override
	public String getName() {
		return "github-full";
	}

	@Override
	public String getPath() {
		return "/mcp/builtin/github-full";
	}

}
