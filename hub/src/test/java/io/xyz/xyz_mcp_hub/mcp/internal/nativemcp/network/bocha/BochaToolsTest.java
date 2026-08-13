package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import java.util.List;

import io.xyz.xyz_mcp_hub.bocha.AiSearchResult;
import io.xyz.xyz_mcp_hub.bocha.BochaClient;
import io.xyz.xyz_mcp_hub.bocha.ModalCard;
import io.xyz.xyz_mcp_hub.bocha.WebPage;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BochaTools 工具类即源测试（#53；#63 单 search 工具 + 策略层）：验证 {@link BochaTools} 作为
 * {@code McpEndpointProvider} 的源元数据（name/scope/enabled/getTools）、单个 {@code search} 工具的
 * {@code type} 路由（默认 ai / type=web）、默认值预设（count 20、freshness noLimit）、ai 的 exclude
 * 忽略、VO 格式化为文本与能力层异常捕获。HTTP 解析由 {@code BochaClientTest}（能力层）覆盖，此处
 * mock BochaClient 只测工具层策略。
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
	void defaultTypePresetsDefaultsAndRoutesToAi() {
		// 工具层预设 count=20、freshness=noLimit、answer=true；exclude 忽略（ai 无此参数）
		when(bochaClient.aiSearch("spring boot", 20, "noLimit", true, null))
			.thenReturn(new AiSearchResult("AI 总结", List.of(), List.of(), List.of()));
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		String text = tools.search(null, "spring boot", null, null, null, "baidu.com");
		assertThat(text).contains("AI 总结");
		verify(bochaClient).aiSearch("spring boot", 20, "noLimit", true, null);
		verify(bochaClient, never()).webSearch(any(), anyInt(), any(), anyBoolean(), any(), any());
	}

	@Test
	void explicitAiTypeRoutesToAiWithDefaults() {
		when(bochaClient.aiSearch("spring boot", 5, "oneMonth", true, "qq.com"))
			.thenReturn(new AiSearchResult("AI 总结", List.of(), List.of(), List.of()));
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search("ai", "spring boot", 5, "oneMonth", "qq.com", "baidu.com"))
			.contains("AI 总结");
		verify(bochaClient).aiSearch("spring boot", 5, "oneMonth", true, "qq.com");
	}

	@Test
	void webTypeRoutesToWebWithAllParams() {
		when(bochaClient.webSearch("spring boot", 5, "oneMonth", true, "qq.com", "baidu.com"))
			.thenReturn(List.of(new WebPage("Spring 官网", "https://spring.io", "Spring", "摘要", "")));
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		String text = tools.search("web", "spring boot", 5, "oneMonth", "qq.com", "baidu.com");
		assertThat(text).contains("Spring 官网");
		assertThat(text).contains("spring.io");
		verify(bochaClient).webSearch("spring boot", 5, "oneMonth", true, "qq.com", "baidu.com");
		verify(bochaClient, never()).aiSearch(any(), anyInt(), any(), anyBoolean(), any());
	}

	@Test
	void formatsAiSummaryModalCardsPagesAndFollowUps() {
		var result = new AiSearchResult(
				"杭州今日晴，25℃。",
				List.of(new WebPage("杭州天气网", "https://weather.example.com/hangzhou", "天气网", "杭州今日天气。", "")),
				List.of(new ModalCard("weather_china", "{\"city\":\"杭州\",\"temperature\":\"25℃\"}")),
				List.of("杭州明天天气如何？", "杭州未来一周天气趋势？"));
		when(bochaClient.aiSearch("杭州天气", 20, "noLimit", true, null)).thenReturn(result);
		BochaTools tools = new BochaTools(bochaClient, "test-key");

		String text = tools.search(null, "杭州天气", null, null, null, null);
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("杭州今日晴");
		assertThat(text).contains("模态卡 · weather_china");
		assertThat(text).contains("\"city\":\"杭州\"");
		assertThat(text).contains("杭州天气网");
		assertThat(text).contains("追问问题");
		assertThat(text).contains("1. 杭州明天天气如何？");
		assertThat(text).contains("2. 杭州未来一周天气趋势？");
	}

	@Test
	void formatsEmptyResultAsNotice() {
		when(bochaClient.webSearch("nothing", 20, "noLimit", true, null, null))
			.thenReturn(List.of());
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		assertThat(tools.search("web", "nothing", null, null, null, null)).contains("未找到相关结果");
	}

	@Test
	void clientErrorReturnsFriendlyMessage() {
		when(bochaClient.webSearch("spring boot", 20, "noLimit", true, null, null))
			.thenThrow(new IllegalStateException("博查搜索失败（code=401）：unauthorized"));
		BochaTools tools = new BochaTools(bochaClient, "test-key");
		String text = tools.search("web", "spring boot", null, null, null, null);
		assertThat(text).contains("博查搜索失败");
		assertThat(text).contains("401");
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
