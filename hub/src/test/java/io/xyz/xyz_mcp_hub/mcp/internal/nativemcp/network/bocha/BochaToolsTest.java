package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import io.xyz.xyz_mcp_hub.bocha.BochaClient;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BochaTools 工具类即源测试（#53，ADR-0015）：验证 {@link BochaTools} 作为 {@code McpEndpointProvider} 的
 * 源元数据（name/scope/enabled/getTools）与单 {@code search} 工具的 type 路由、默认值映射（mock
 * BochaClient 只测工具层；HTTP 格式化由 {@code BochaClientTest} 能力层覆盖）。
 */
class BochaToolsTest {

	private final BochaClient bochaClient = mock(BochaClient.class);

	@Test
	void exposesSourceMetadata() {
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.getName()).isEqualTo("bocha");
		assertThat(tools.getScope()).isEqualTo(Scope.NETWORK);
		// 默认 SourceType.NATIVE（包装 HTTP API），无 protocol
		assertThat(tools.getSourceType()).isEqualTo(SourceType.NATIVE);
		assertThat(tools.getProtocol()).isNull();
		// 合成后单 search 工具（ADR-0015）
		assertThat(tools.getTools()).hasSize(1);
	}

	@Test
	void enabledWithApiKey() {
		assertThat(new BochaTools(bochaClient, "test-key").isEnabled()).isTrue();
	}

	@Test
	void disabledWithoutApiKey() {
		assertThat(new BochaTools(bochaClient, "").isEnabled()).isFalse();
		assertThat(new BochaTools(bochaClient, "  ").isEnabled()).isFalse();
	}

	@Test
	void searchDefaultsToAiWithDefaultCount() {
		// type 缺省 → ai；count 缺省 → 20；answer 恒 true
		when(bochaClient.aiSearch("spring boot", 20, null, null, true)).thenReturn("AI 总结");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search(null, "spring boot", null, null, null, null)).isEqualTo("AI 总结");
		verify(bochaClient).aiSearch("spring boot", 20, null, null, true);
	}

	@Test
	void searchTypeWebRoutesToWebSearch() {
		// type=web → webSearch，summary 恒 true，include/exclude 透传
		when(bochaClient.webSearch("spring boot", 20, null, true, "qq.com", "m.163.com")).thenReturn("网页列表");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search("web", "spring boot", null, null, "qq.com", "m.163.com")).isEqualTo("网页列表");
		verify(bochaClient).webSearch("spring boot", 20, null, true, "qq.com", "m.163.com");
	}

	@Test
	void searchTypeAiIgnoresExclude() {
		// type=ai：exclude 官网不支持 → 不传给能力层（aiSearch 无 exclude 参数），include 透传
		when(bochaClient.aiSearch("spring boot", 20, null, "qq.com", true)).thenReturn("AI 总结");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search("ai", "spring boot", null, null, "qq.com", "m.163.com")).isEqualTo("AI 总结");
		verify(bochaClient).aiSearch("spring boot", 20, null, "qq.com", true);
	}

	@Test
	void searchPassesExplicitCountAndFreshness() {
		when(bochaClient.webSearch("spring boot", 50, "oneMonth", true, null, null)).thenReturn("网页列表");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search("web", "spring boot", 50, "oneMonth", null, null)).isEqualTo("网页列表");
		verify(bochaClient).webSearch("spring boot", 50, "oneMonth", true, null, null);
	}

}
