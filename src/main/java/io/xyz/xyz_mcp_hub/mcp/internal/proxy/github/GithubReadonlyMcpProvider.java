package io.xyz.xyz_mcp_hub.mcp.internal.proxy.github;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub 只读代理 MCP 端点提供者，暴露 {@code /mcp/server/github-readonly}。
 *
 * <p>同一上游，但经 {@link #getToolNames()} 固定只读工具清单过滤（ADR-0007 决策 3，不建通用过滤
 * 机制），仅透传只读工具、不透传任何写操作。清单为 github-mcp-server 源码中标记只读
 * （ReadOnlyHint）的工具名。认证与 {@link GithubFullMcpProvider} 相同。</p>
 */
@Component
public class GithubReadonlyMcpProvider extends AbstractGithubMcpProvider {

	private static final List<String> READONLY_TOOLS = List.of(
			"get_me",
			"get_teams",
			"get_commit",
			"get_file_contents",
			"get_repository_tree",
			"get_gist",
			"get_discussion",
			"list_commits",
			"list_branches",
			"list_issues",
			"list_pull_requests",
			"list_discussions",
			"list_gists",
			"search_repositories",
			"search_code",
			"search_users",
			"search_issues",
			"search_pull_requests");

	public GithubReadonlyMcpProvider(
			@Value("${github.upstream-url:https://api.githubcopilot.com/mcp/}") String upstreamUrl,
			@Value("${github.token:}") String token) {
		super(upstreamUrl, token);
	}

	@Override
	public String getName() {
		return "github-readonly";
	}

	@Override
	public String getPath() {
		return "/mcp/server/github-readonly";
	}

	@Override
	public List<String> getToolNames() {
		return READONLY_TOOLS;
	}

}
