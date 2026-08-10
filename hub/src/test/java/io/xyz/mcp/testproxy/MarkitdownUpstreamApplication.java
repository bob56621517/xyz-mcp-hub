package io.xyz.mcp.testproxy;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 集成测试专用：内嵌 markitdown 模拟 MCP Server 应用。独立 context，暴露
 * {@code convert_to_markdown(uri)} 工具（返回固定 Markdown，记录收到的 uri 供断言）。
 *
 * <p>用于验证 markitdown 容器源（#37）的 MCP 转发链路（uri 传参、结果解析），
 * 不依赖真实 markitdown 环境。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(MarkitdownUpstreamRegistrar.class)
public class MarkitdownUpstreamApplication {
}
