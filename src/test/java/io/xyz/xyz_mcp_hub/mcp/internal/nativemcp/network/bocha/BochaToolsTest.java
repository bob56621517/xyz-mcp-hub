package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * BochaTools 单元测试：用 MockRestServiceServer 模拟博查 HTTP API，验证工具调用与结果格式化。
 */
class BochaToolsTest {

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

	private MockRestServiceServer server;
	private BochaTools tools;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		tools = new BochaTools(builder.build());
	}

	@Test
	void webSearchFormatsResults() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess(WEB_SEARCH_JSON, MediaType.APPLICATION_JSON));

		String text = tools.webSearch("spring boot", 5, "oneMonth");
		assertThat(text).contains("Spring Boot 官网");
		assertThat(text).contains("spring.io/projects/spring-boot");
		assertThat(text).contains("Spring");
		assertThat(text).contains("快速构建生产级");
	}

	@Test
	void aiSearchIncludesSummary() {
		server.expect(requestTo(BASE_URL + "/v1/ai-search"))
			.andRespond(withSuccess(AI_SEARCH_JSON, MediaType.APPLICATION_JSON));

		String text = tools.aiSearch("spring boot", null, null);
		assertThat(text).contains("AI 总结");
		assertThat(text).contains("流行的 Java 微服务框架");
		assertThat(text).contains("Spring Boot 官网");
	}

	@Test
	void nonSuccessCodeReturnsErrorText() {
		server.expect(requestTo(BASE_URL + "/v1/web-search"))
			.andRespond(withSuccess("""
				{"code": 401, "msg": "unauthorized"}
				""", MediaType.APPLICATION_JSON));

		String text = tools.webSearch("spring boot", null, null);
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

		String text = tools.webSearch("nothing", null, null);
		assertThat(text).contains("未找到相关结果");
	}

}
