package io.xyz.mcp.testproxy;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 集成测试专用：内嵌上游 MCP Server 应用。独立 context，暴露已知工具列表（echo / fail）。
 *
 * <p>与主应用同享 classpath 与 {@code application.yaml}（含 spring.autoconfigure.exclude），
 * 但端点注册完全走 {@link UpstreamEndpointRegistrar}，避免与 Hub 的多端点注册器混淆。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(UpstreamEndpointRegistrar.class)
public class UpstreamMcpApplication {
}
