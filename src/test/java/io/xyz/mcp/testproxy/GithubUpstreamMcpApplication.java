package io.xyz.mcp.testproxy;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 集成测试专用：GitHub 风格内嵌上游 MCP Server 应用。独立 context，暴露已知工具列表
 * （get_me / search_issues / create_issue），模拟 GitHub 远程 MCP 的读写工具混合场景。
 *
 * <p>与主应用同享 classpath 与 {@code application.yaml}（含 spring.autoconfigure.exclude），
 * 但端点注册完全走 {@link GithubUpstreamEndpointRegistrar}，避免与 Hub 的多端点注册器混淆。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(GithubUpstreamEndpointRegistrar.class)
public class GithubUpstreamMcpApplication {
}
