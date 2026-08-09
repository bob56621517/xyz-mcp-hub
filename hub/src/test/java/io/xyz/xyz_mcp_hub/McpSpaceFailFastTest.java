package io.xyz.xyz_mcp_hub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Space 配置错误 fail-fast 测试（ADR-0008）：include 引用源端点不存在的工具时，
 * 应用启动即失败，便于立刻发现配置错误。
 */
class McpSpaceFailFastTest {

	@Test
	void includeOfMissingToolFailsStartup() {
		assertThatThrownBy(() -> new SpringApplicationBuilder(XyzMcpHubApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0",
					"mcp.spaces.bad.sources[0].source=utils",
					"mcp.spaces.bad.sources[0].include[0]=nonexistent")
			.run())
			.hasStackTraceContaining("nonexistent");
	}

}
