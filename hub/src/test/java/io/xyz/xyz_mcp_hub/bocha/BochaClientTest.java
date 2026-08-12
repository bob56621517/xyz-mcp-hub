package io.xyz.xyz_mcp_hub.bocha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * bocha 顶级模块能力测试（#53，S1 能力层；ADR-0015）：用 MockRestServiceServer 模拟博查 HTTP API，验证
 * {@link BochaClient} 的搜索调用与结果格式化（不触网、不经 MCP）。覆盖官网参数透传（include/exclude/
 * summary/answer）与 ai 响应的追问问题/模态卡解析。
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
		          "summary": "Spring Boot 让构建独立、生产级 Spring 应用变得简单。",
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

	/** ai 响应含追问问题（follow_up）与天气模态卡（weather_china，数组项含 modelCard）。 */
	private static final String AI_WITH_CARDS_JSON = """
		{
		  "code": 200,
		  "messages": [
		    {"type":"source","content_type":"webpage","content":"{\\"webSearchUrl\\":\\"https://bochaai.com/search?q=北京天气\\",\\"value\\":[{\\"name\\":\\"114天气网\\",\\"url\\":\\"http://www.beijingtianqi114.com/\\",\\"snippet\\":\\"北京天气预报\\",\\"siteName\\":\\"114\\"}]}"},
		    {"type":"source","content_type":"weather_china","content":"[{\\"name\\":\\"北京\\",\\"url\\":\\"https://www.weatherol.com.cn\\",\\"modelCard\\":{\\"day\\":[{\\"date\\":\\"2026-08-11\\",\\"description_day\\":\\"雷阵雨\\"}]}}]"},
		    {"type":"answer","content_type":"text","content":"北京天气属于暖温带半湿润大陆性季风气候。"},
		    {"type":"follow_up","content_type":"text","content":"[\\"北京未来一周天气趋势？\\",\\"北京当前空气质量？\\"]"}
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

		String text = client.webSearch("spring boot", 5, "oneMonth", null, null, null);
		assertThat(text).contains("Spring Boot 官网");
		assertThat(text).contains("spring.io/projects/spring-boot");
		assertThat(text).contains("Spring");
		// summary=true 时长摘要优先于 snippet
		assertThat(text).contains("让构建独立、生产级 Spring 应用变得简单");
	}

	@Test
	void webSearchPassesSummaryIncludeExclude() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andExpect(jsonPath("$.summary").value(true))
			.andExpect(jsonPath("$.include").value("qq.com"))
			.andExpect(jsonPath("$.exclude").value("m.163.com"))
			.andExpect(jsonPath("$.count").value(20))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.webSearch("spring boot", 20, "noLimit", true, "qq.com", "m.163.com");
	}

	@Test
	void aiSearchIncludesSummary() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		String text = client.aiSearch("spring boot", null, null, null, null);
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("流行的 Java 微服务框架");
		assertThat(text).contains("Spring Boot 官网");
	}

	@Test
	void aiSearchPassesIncludeAnswer() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andExpect(jsonPath("$.include").value("qq.com"))
			.andExpect(jsonPath("$.answer").value(true))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		client.aiSearch("spring boot", null, null, "qq.com", true);
	}

	@Test
	void aiSearchParsesFollowUpAndModelCard() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_WITH_CARDS_JSON, MediaType.APPLICATION_JSON));

		String text = client.aiSearch("北京天气", null, null, null, null);
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("暖温带半湿润大陆性季风气候");
		// 追问问题：follow_up content 为 JSON 数组字符串，呈现为编号列表
		assertThat(text).contains("追问问题");
		assertThat(text).contains("北京未来一周天气趋势");
		assertThat(text).contains("北京当前空气质量");
		// 模态卡：结构化 JSON 直接返回
		assertThat(text).contains("模态卡[weather_china]");
		assertThat(text).contains("modelCard");
		assertThat(text).contains("\"description_day\":\"雷阵雨\"");
		// 网页参考仍返回
		assertThat(text).contains("114天气网");
	}

	@Test
	void nonSuccessCodeReturnsErrorText() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess("""
				{"code": 401, "msg": "unauthorized"}
				""", MediaType.APPLICATION_JSON));

		String text = client.webSearch("spring boot", null, null, null, null, null);
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

		String text = client.webSearch("nothing", null, null, null, null, null);
		assertThat(text).contains("未找到相关结果");
	}

}
