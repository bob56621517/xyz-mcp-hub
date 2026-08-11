package io.xyz.xyz_mcp_hub.bocha;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * bocha 顶级模块的纯能力客户端（与 docker/playwright 同级，#53）：封装博查 Web Search 与 AI Search
 * HTTP API，返回格式化搜索结果文本。
 *
 * <p>零 MCP/Spring AI 依赖（仅用 spring-web 的 {@link RestClient} 与 jackson），可独立复用与测试。
 * 博查 API 文档：{@code POST https://api.bochaai.com/v1/web-search}（网页搜索）与
 * {@code /v1/ai-search}（AI 搜索），请求头 {@code Authorization: Bearer <api-key>}。</p>
 */
public class BochaClient {

	private static final int DEFAULT_COUNT = 10;
	private static final int MAX_COUNT = 50;
	private static final String DEFAULT_FRESHNESS = "noLimit";
	private static final List<String> FRESHNESS_VALUES = List.of(
			"noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear");

	private final RestClient restClient;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public BochaClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/** 网页搜索：从全网检索网页结果，返回标题、链接、站点与摘要。 */
	public String webSearch(String query, Integer count, String freshness) {
		return search("web-search", query, count, freshness);
	}

	/** AI 搜索：在全网搜索基础上返回 AI 总结答案与参考源，适合需要综述回答的场景。 */
	public String aiSearch(String query, Integer count, String freshness) {
		return search("ai-search", query, count, freshness);
	}

	private String search(String endpoint, String query, Integer count, String freshness) {
		if (query == null || query.isBlank()) {
			return "请提供搜索关键词 query。";
		}
		int n = count == null ? DEFAULT_COUNT : Math.max(1, Math.min(count, MAX_COUNT));
		String fresh = (freshness == null || freshness.isBlank() || !FRESHNESS_VALUES.contains(freshness))
			? DEFAULT_FRESHNESS
			: freshness;

		Map<String, Object> requestBody = "ai-search".equals(endpoint)
			? Map.of("query", query, "count", n, "freshness", fresh)
			: Map.of("query", query, "count", n, "freshness", fresh, "summary", true);

		String requestJson;
		try {
			requestJson = jsonMapper.writeValueAsString(requestBody);
		} catch (JacksonException e) {
			throw new IllegalStateException("无法序列化博查请求参数", e);
		}

		String responseBody = restClient.post()
			.uri("/v1/" + endpoint)
			.contentType(MediaType.APPLICATION_JSON)
			.body(requestJson)
			.retrieve()
			.body(String.class);

		return formatResponse(endpoint, responseBody);
	}

	private String formatResponse(String endpoint, String responseBody) {
		JsonNode root;
		try {
			root = jsonMapper.readTree(responseBody);
		} catch (JacksonException e) {
			return "博查搜索失败：无法解析响应\n" + responseBody;
		}

		int code = root.path("code").asInt(-1);
		if (code != 200) {
			String msg = root.path("msg").asText("");
			if (msg.isBlank()) {
				msg = root.path("message").asText("");
			}
			return "博查搜索失败（code=" + code + "）：" + msg;
		}

		StringBuilder sb = new StringBuilder();
		if ("ai-search".equals(endpoint)) {
			appendAiSearch(sb, root);
		}
		else {
			appendPages(sb, root.path("data").path("webPages").path("value"));
		}
		if (sb.isEmpty()) {
			sb.append("未找到相关结果。");
		}
		return sb.toString().stripTrailing();
	}

	/**
	 * ai-search 响应结构：顶层 {@code messages[]}。{@code type=answer} 的消息 content 为纯文本
	 * AI 总结；{@code type=source, content_type=webpage} 的消息 content 为内嵌 JSON 字符串，
	 * 含 {@code value[]} 网页列表（字段与 web-search 的 webPages.value 一致）。
	 */
	private void appendAiSearch(StringBuilder sb, JsonNode root) {
		for (JsonNode message : root.path("messages")) {
			String type = message.path("type").asText("");
			String content = message.path("content").asText("");
			if ("answer".equals(type) && !content.isBlank()) {
				sb.append("AI 总结：").append(content).append("\n\n");
				break;
			}
		}
		for (JsonNode message : root.path("messages")) {
			String type = message.path("type").asText("");
			String contentType = message.path("content_type").asText("");
			String content = message.path("content").asText("");
			if ("source".equals(type) && "webpage".equals(contentType) && !content.isBlank()) {
				try {
					appendPages(sb, jsonMapper.readTree(content).path("value"));
				}
				catch (JacksonException ignored) {
					// 单条 source 解析失败时忽略，继续后续消息
				}
				break;
			}
		}
	}

	private void appendPages(StringBuilder sb, JsonNode pages) {
		if (!pages.isArray() || pages.isEmpty()) {
			return;
		}
		int i = 1;
		for (JsonNode page : pages) {
			String name = page.path("name").asText("");
			String url = page.path("url").asText("");
			String siteName = page.path("siteName").asText("");
			String snippet = page.path("snippet").asText("");

			sb.append(i++).append(". ");
			if (!name.isBlank()) {
				sb.append(name);
			}
			if (!url.isBlank()) {
				sb.append("（").append(url).append("）");
			}
			if (!siteName.isBlank()) {
				sb.append(" [").append(siteName).append("]");
			}
			sb.append('\n');
			if (!snippet.isBlank()) {
				sb.append("   ").append(snippet).append('\n');
			}
		}
	}

}
