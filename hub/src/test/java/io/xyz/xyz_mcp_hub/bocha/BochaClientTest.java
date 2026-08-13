package io.xyz.xyz_mcp_hub.bocha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * bocha 顶级模块能力测试（#53，S1 能力层；#63 能力层为纯 API 封装）：用 MockRestServiceServer 模拟博查
 * HTTP API，验证 {@link BochaClient} 的请求体参数透传（count/freshness/summary/answer/include/exclude）
 * 与响应解析为 VO（网页/总结/模态卡/追问问题），以及失败抛异常。不触网、不经 MCP。
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
		          "summary": "更长的一段摘要。",
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

	/** ai 含模态卡（weather_china）与追问问题（follow_up）。 */
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
	void webSearchParsesPagesIntoVo() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		var pages = client.webSearch("spring boot", 5, "oneMonth", true, null, null);
		assertThat(pages).hasSize(1);
		assertThat(pages.get(0).name()).isEqualTo("Spring Boot 官网");
		assertThat(pages.get(0).url()).isEqualTo("https://spring.io/projects/spring-boot");
		assertThat(pages.get(0).snippet()).contains("快速构建生产级");
		assertThat(pages.get(0).summary()).contains("更长的一段摘要");
		assertThat(pages.get(0).siteName()).isEqualTo("Spring");
	}

	@Test
	void webSearchForwardsParamsAndSummary() {
		// 请求体：count/freshness 显式透传、summary=true、include/exclude 全透传
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andExpect(content().json("""
				{"query":"spring boot","count":5,"freshness":"oneMonth","summary":true,
				 "include":"qq.com|m.163.com","exclude":"baidu.com"}
				"""))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.webSearch("spring boot", 5, "oneMonth", true, "qq.com|m.163.com", "baidu.com");
		server.verify();
	}

	@Test
	void webSearchOmitsNullParams() {
		// count/freshness/include/exclude 为 null、summary=false 时均不传（交官网默认）
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andExpect(content().json("""
				{"query":"spring boot"}
				"""))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.webSearch("spring boot", null, null, false, null, null);
		server.verify();
	}

	@Test
	void aiSearchParsesSummaryPagesIntoVo() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		var result = client.aiSearch("spring boot", null, null, true, null);
		assertThat(result.summary()).contains("流行的 Java 微服务框架");
		assertThat(result.pages()).hasSize(1);
		assertThat(result.pages().get(0).name()).isEqualTo("Spring Boot 官网");
		assertThat(result.modalCards()).isEmpty();
		assertThat(result.followUpQuestions()).isEmpty();
	}

	@Test
	void aiSearchParsesModelCardAndFollowUp() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_MODAL_CARD_JSON, MediaType.APPLICATION_JSON));

		var result = client.aiSearch("杭州天气", 5, "oneDay", true, "weather.example.com");
		assertThat(result.summary()).contains("杭州今日晴");
		// 模态卡：结构化 JSON 原样保留（紧凑保真，不转自然语言）
		assertThat(result.modalCards()).hasSize(1);
		ModalCard card = result.modalCards().get(0);
		assertThat(card.contentType()).isEqualTo("weather_china");
		assertThat(card.modelCardJson()).contains("\"city\":\"杭州\"").contains("\"humidity\":\"40%\"");
		// 网页参考源 + 追问问题
		assertThat(result.pages()).hasSize(1);
		assertThat(result.pages().get(0).name()).isEqualTo("杭州天气网");
		assertThat(result.followUpQuestions()).containsExactly("杭州明天天气如何？", "杭州未来一周天气趋势？");
	}

	@Test
	void aiSearchForwardsAnswerAndInclude() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andExpect(content().json("""
				{"query":"spring boot","answer":true,"include":"qq.com"}
				"""))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.aiSearch("spring boot", null, null, true, "qq.com");
		server.verify();
	}

	@Test
	void nonSuccessCodeThrows() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess("""
				{"code": 401, "msg": "unauthorized"}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.webSearch("spring boot", null, null, true, null, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("401")
			.hasMessageContaining("unauthorized");
	}

	@Test
	void emptyResultReturnsEmptyList() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess("""
				{"code": 200, "data": {"webPages": {"value": []}}}
				""", MediaType.APPLICATION_JSON));

		var pages = client.webSearch("nothing", null, null, true, null, null);
		assertThat(pages).isEmpty();
	}

	@Test
	void blankQueryThrows() {
		assertThatThrownBy(() -> client.webSearch("  ", null, null, true, null, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("query");
	}

}
