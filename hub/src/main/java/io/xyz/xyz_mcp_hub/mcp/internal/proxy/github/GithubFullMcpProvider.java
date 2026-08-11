package io.xyz.xyz_mcp_hub.mcp.internal.proxy.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub 全量代理 MCP 源提供者（工具经单端点 {@code /xyz-hub/mcp?includes=[github-full]} 暴露）。
 *
 * <p>透传 GitHub 官方远程托管 MCP Server（{@code https://api.githubcopilot.com/mcp/}）的全部工具，
 * 含读写操作。认证用 GitHub Personal Access Token（Bearer header），经 Spring 配置注入（ADR-0005）。
 * 缺少 {@code github.token} 时源未启用（已注册、目录列出 enabled=false、工具为空，见 ADR-0005
 * 二次修订 / #50）。</p>
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

}
