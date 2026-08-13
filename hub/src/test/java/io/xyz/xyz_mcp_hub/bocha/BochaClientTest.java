package io.xyz.xyz_mcp_hub.bocha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * bocha 顶级模块能力测试（#53，S1 能力层；#63 能力层忠于官网）：用 MockRestServiceServer 模拟博查
 * HTTP API，验证 {@link BochaClient} 的请求体参数透传（count/freshness 默认、include/exclude、
 * summary/answer 策略）与结果格式化（网页/总结/模态卡/追问问题，不触网、不经 MCP）。
 */
class BochaClientTest {

	private static final String BASE_URL = "https://api.bochaai.com";

	private static final String WEB_SEARCH_JSON = """
		{
		  "code": 200,
		  "data": {
		    "webPages": {
		      "value": [
		        {
		          "name": "Spring Boot 官网",
		          "url": "https://spring.io/projects/spring-boot",
		          "snippet": "快速构建生产级 Spring 应用。",
		          "siteName": "Spring"
		        }
		      ]
		    }
		  }
		}
		""";

	private static final String AI_SEARCH_JSON = """
		{
		  "code": 200,
		  "log_id": "test-log",
		  "messages": [
		    {
		      "role": "assistant",
		      "type": "answer",
		      "content_type": "text",
		      "content": "Spring Boot 是流行的 Java 微服务框架。"
		    },
		    {
		      "role": "assistant",
		      "type": "source",
		      "content_type": "webpage",
		      "content": "{\\"webSearchUrl\\":\\"https://bochaai.com/search?q=spring boot\\",\\"value\\":[{\\"name\\":\\"Spring Boot 官网\\",\\"url\\":\\"https://spring.io/projects/spring-boot\\",\\"snippet\\":\\"快速构建生产级 Spring 应用。\\",\\"siteName\\":\\"Spring\\"}]}"
		    }
		  ]
		}
		""";

	/** ai 含模态卡（weather_china）与追问问题（follow_up）的响应。 */
	private static final String AI_MODAL_CARD_JSON = """
		{
		  "code": 200,
		  "log_id": "modal-test",
		  "messages": [
		    {
		      "role": "assistant",
		      "type": "answer",
		      "content_type": "text",
		      "content": "杭州今日晴，25℃。"
		    },
		    {
		      "role": "assistant",
		      "type": "source",
		      "content_type": "weather_china",
		      "content": "[{\\"modelCard\\":{\\"city\\":\\"杭州\\",\\"weather\\":\\"晴\\",\\"temperature\\":\\"25℃\\",\\"humidity\\":\\"40%\\"}}]"
		    },
		    {
		      "role": "assistant",
		      "type": "source",
		      "content_type": "webpage",
		      "content": "{\\"webSearchUrl\\":\\"https://bochaai.com/search?q=杭州天气\\",\\"value\\":[{\\"name\\":\\"杭州天气网\\",\\"url\\":\\"https://weather.example.com/hangzhou\\",\\"snippet\\":\\"杭州今日天气。\\",\\"siteName\\":\\"天气网\\"}]}"
		    },
		    {
		      "role": "assistant",
		      "type": "follow_up",
		      "content_type": "text",
		      "content": "[\\"杭州明天天气如何？\\",\\"杭州未来一周天气趋势？\\"]"
		    }
		  ]
		}
		""";

	private MockRestServiceServer server;
	private BochaClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new BochaClient(builder.build());
	}

	@Test
	void webSearchFormatsResults() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		String text = client.webSearch("spring boot", 5, "oneMonth", null, null);
		assertThat(text).contains("Spring Boot 官网");
		assertThat(text).contains("spring.io/projects/spring-boot");
		assertThat(text).contains("Spring");
		assertThat(text).contains("快速构建生产级");
	}

	@Test
	void webSearchForwardsIncludeExcludeSummaryAndDefaults() {
		// 请求体：query/count 默认 20/freshness 默认 noLimit/summary=true/include/exclude 全透传
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andExpect(content().json("""
				{"query":"spring boot","count":20,"freshness":"noLimit","summary":true,
				 "include":"qq.com|m.163.com","exclude":"baidu.com"}
				"""))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.webSearch("spring boot", null, null, "qq.com|m.163.com", "baidu.com");
		server.verify();
	}

	@Test
	void aiSearchForwardsIncludeAnswerAndDefaultsWithoutExclude() {
		// 请求体：query/count/freshness 默认/answer=true/include 透传；AI 无 exclude 参数（能力层无此形参）
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andExpect(content().json("""
				{"query":"spring boot","count":20,"freshness":"noLimit","answer":true,"include":"qq.com"}
				"""))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.aiSearch("spring boot", null, null, "qq.com");
		server.verify();
	}

	@Test
	void explicitCountAndFreshnessAreForwarded() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andExpect(content().json("""
				{"query":"spring boot","count":5,"freshness":"2025-01-01..2025-04-06","summary":true}
				"""))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.webSearch("spring boot", 5, "2025-01-01..2025-04-06", null, null);
		server.verify();
	}

	@Test
	void invalidFreshnessFallsBackToNoLimit() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andExpect(content().json("""
				{"query":"spring boot","count":20,"freshness":"noLimit","summary":true}
				"""))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.webSearch("spring boot", null, "lastWeek", null, null);
		server.verify();
	}

	@Test
	void aiSearchIncludesSummary() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		String text = client.aiSearch("spring boot", null, null, null);
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("流行的 Java 微服务框架");
		assertThat(text).contains("Spring Boot 官网");
	}

	@Test
	void aiSearchParsesModelCardAndFollowUp() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_MODAL_CARD_JSON, MediaType.APPLICATION_JSON));

		String text = client.aiSearch("杭州天气", 5, "oneDay", "weather.example.com");
		// AI 总结
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("杭州今日晴");
		// 模态卡：结构化 JSON 原样返回（JSON 紧凑保真，不转自然语言）
		assertThat(text).contains("模态卡 · weather_china");
		assertThat(text).contains("\"city\":\"杭州\"");
		assertThat(text).contains("\"humidity\":\"40%\"");
		// 网页参考源
		assertThat(text).contains("杭州天气网");
		// 追问问题列表
		assertThat(text).contains("追问问题");
		assertThat(text).contains("1. 杭州明天天气如何？");
		assertThat(text).contains("2. 杭州未来一周天气趋势？");
	}

	@Test
	void nonSuccessCodeReturnsErrorText() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess("""
				{"code": 401, "msg": "unauthorized"}
				""", MediaType.APPLICATION_JSON));

		String text = client.webSearch("spring boot", null, null, null, null);
		assertThat(text).contains("博查搜索失败");
		assertThat(text).contains("401");
		assertThat(text).contains("unauthorized");
	}

	@Test
	void emptyResultReturnsNotice() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess("""
				{"code": 200, "data": {"webPages": {"value": []}}}
				""", MediaType.APPLICATION_JSON));

		String text = client.webSearch("nothing", null, null, null, null);
		assertThat(text).contains("未找到相关结果");
	}

}
