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
 * BochaTools 工具类即源测试（#53）：验证 {@link BochaTools} 作为 {@code McpEndpointProvider} 的源元数据
 * （name/scope/enabled/getTools）与 {@code @Tool} 方法对纯能力 {@link BochaClient} 的委托转发。
 * HTTP 搜索格式化由 {@code BochaClientTest}（能力层）覆盖，此处 mock BochaClient 只测工具类。
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
		// web_search / ai_search 两个工具注册
		assertThat(tools.getTools()).hasSize(2);
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
	void webSearchDelegatesToClient() {
		when(bochaClient.webSearch("spring boot", 5, "oneMonth")).thenReturn("mock 结果");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.webSearch("spring boot", 5, "oneMonth")).isEqualTo("mock 结果");
		verify(bochaClient).webSearch("spring boot", 5, "oneMonth");
	}

	@Test
	void aiSearchDelegatesToClient() {
		when(bochaClient.aiSearch("spring boot", null, null)).thenReturn("AI 总结");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.aiSearch("spring boot", null, null)).isEqualTo("AI 总结");
		verify(bochaClient).aiSearch("spring boot", null, null);
	}

}
