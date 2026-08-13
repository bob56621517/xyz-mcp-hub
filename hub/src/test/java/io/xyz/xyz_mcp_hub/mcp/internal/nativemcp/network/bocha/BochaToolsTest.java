package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import io.xyz.xyz_mcp_hub.bocha.BochaClient;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BochaTools 工具类即源测试（#53；#63 单 search 工具）：验证 {@link BochaTools} 作为
 * {@code McpEndpointProvider} 的源元数据（name/scope/enabled/getTools）与单个 {@code search} 工具的
 * {@code type} 路由（默认 ai / type=web）、默认值映射（count 20、freshness noLimit）、ai 的 exclude
 * 忽略与描述文案非空。HTTP 搜索格式化由 {@code BochaClientTest}（能力层）覆盖，此处 mock BochaClient
 * 只测工具类。
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
		// 单 search 工具（暴露名由源注册表加 bocha_ 前缀）
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
	void defaultTypeRoutesToAiSearch() {
		when(bochaClient.aiSearch("spring boot", 20, "noLimit", null)).thenReturn("AI 总结");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search(null, "spring boot", null, null, null, null)).isEqualTo("AI 总结");
		verify(bochaClient).aiSearch("spring boot", 20, "noLimit", null);
		verify(bochaClient, never()).webSearch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

	@Test
	void explicitAiTypeRoutesToAiSearch() {
		when(bochaClient.aiSearch("spring boot", 5, "oneMonth", "qq.com")).thenReturn("AI 总结");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		// ai 的 exclude 被忽略，不传给 aiSearch（能力层无 exclude 形参）
		assertThat(tools.search("ai", "spring boot", 5, "oneMonth", "qq.com", "baidu.com")).isEqualTo("AI 总结");
		verify(bochaClient).aiSearch("spring boot", 5, "oneMonth", "qq.com");
	}

	@Test
	void webTypeRoutesToWebSearchWithAllParams() {
		when(bochaClient.webSearch("spring boot", 5, "oneMonth", "qq.com", "baidu.com")).thenReturn("网页结果");
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		// web 全透传：include + exclude 都进 webSearch
		assertThat(tools.search("web", "spring boot", 5, "oneMonth", "qq.com", "baidu.com")).isEqualTo("网页结果");
		verify(bochaClient).webSearch("spring boot", 5, "oneMonth", "qq.com", "baidu.com");
		verify(bochaClient, never()).aiSearch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void searchToolDescriptionGuidesModel() {
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		String description = tools.getTools().get(0).getToolDefinition().description();
		assertThat(description).contains("联网工具");
		assertThat(description).contains("AI 语义搜索");
		assertThat(description).contains("type=\"web\"");
		assertThat(description).contains("include");
		assertThat(description).contains("exclude");
		assertThat(description).contains("超链接");
	}

}
