package io.xyz.xyz_mcp_hub.bocha;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * bocha 顶级模块的纯能力客户端（与 docker/playwright 同级，#53）：封装博查 Web Search 与 AI Search
 * HTTP API，返回格式化搜索结果文本。**忠于官网**（ADR-0015）：透传官网参数、不代模型决策；默认值仅作
 * 兜底（工具层传 AI 习惯值）。
 *
 * <p>零 MCP/Spring AI 依赖（仅用 spring-web 的 {@link RestClient} 与 jackson），可独立复用与测试。
 * 博查 API 文档：{@code POST https://api.bocha.cn/v1/web-search}（网页搜索）与
 * {@code /v1/ai-search}（AI 搜索），请求头 {@code Authorization: Bearer <api-key>}。base-url 由
 * {@code bocha.base-url} 配置层决定（ADR-0015，默认 api.bochaai.com）。</p>
 */
public class BochaClient {

	/** 能力层兜底 count（忠于官网的缺省值）；AI 习惯默认 20 由工具层 {@code BochaTools.DEFAULT_COUNT} 决定（ADR-0015）。 */
	private static final int FALLBACK_COUNT = 10;
	private static final int MAX_COUNT = 50;
	private static final String DEFAULT_FRESHNESS = "noLimit";
	private static final List<String> FRESHNESS_VALUES = List.of(
			"noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear");
	/** 官网另支持日期 / 日期范围：YYYY-MM-DD 或 YYYY-MM-DD..YYYY-MM-DD。 */
	private static final Pattern DATE_RANGE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(\\.\\.\\d{4}-\\d{2}-\\d{2})?");

	private final RestClient restClient;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public BochaClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/** 网页搜索：从全网检索网页结果，返回标题、链接、站点与摘要。summary/include/exclude 均透传官网。 */
	public String webSearch(String query, Integer count, String freshness, Boolean summary, String include,
			String exclude) {
		Map<String, Object> body = baseBody(query, count, freshness);
		putIfPresent(body, "summary", summary);
		putIfPresent(body, "include", include);
		putIfPresent(body, "exclude", exclude);
		return postSearch("web-search", body);
	}

	/** AI 搜索：返回 AI 总结答案、追问问题与参考来源（含模态卡）。include/answer 透传官网。 */
	public String aiSearch(String query, Integer count, String freshness, String include, Boolean answer) {
		Map<String, Object> body = baseBody(query, count, freshness);
		putIfPresent(body, "include", include);
		putIfPresent(body, "answer", answer);
		return postSearch("ai-search", body);
	}

	/** 两端点共用的请求体基座：query / count（1-50）/ freshness（非法值兜底 noLimit）。 */
	private static Map<String, Object> baseBody(String query, Integer count, String freshness) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", query);
		body.put("count", count == null ? FALLBACK_COUNT : clamp(count));
		body.put("freshness", (freshness != null && !freshness.isBlank() && isValidFreshness(freshness))
				? freshness : DEFAULT_FRESHNESS);
		return body;
	}

	private static int clamp(int count) {
		return Math.max(1, Math.min(count, MAX_COUNT));
	}

	private static boolean isValidFreshness(String value) {
		return FRESHNESS_VALUES.contains(value) || DATE_RANGE.matcher(value).matches();
	}

	private static void putIfPresent(Map<String, Object> body, String key, Object value) {
		if (value != null) {
			body.put(key, value);
		}
	}

	private String postSearch(String endpoint, Map<String, Object> requestBody) {
		String requestJson;
		try {
			requestJson = jsonMapper.writeValueAsString(requestBody);
		}
		catch (JacksonException e) {
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
		}
		catch (JacksonException e) {
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
	 * ai-search 响应结构：顶层 {@code messages[]} 各 type。{@code answer}=总结文本；
	 * {@code follow_up}=追问问题（content 为 JSON 数组字符串）；{@code source, content_type=webpage}=
	 * 网页参考（content 内嵌 JSON 含 value[]）；{@code source} 其余 content_type（weather_china /
	 * baike_pro / medical / …）=模态卡，数组项含 {@code modelCard} 结构化字段。
	 */
	private void appendAiSearch(StringBuilder sb, JsonNode root) {
		appendAnswer(sb, root);
		appendFollowUp(sb, root);
		// ADR-0015 顺序：总结答案 + 追问问题 + 参考来源（网页）+ 模态卡
		appendWebpageSources(sb, root);
		appendModelCards(sb, root);
	}

	private void appendAnswer(StringBuilder sb, JsonNode root) {
		for (JsonNode message : root.path("messages")) {
			String type = message.path("type").asText("");
			String content = message.path("content").asText("");
			if ("answer".equals(type) && !content.isBlank()) {
				sb.append("AI 总结：").append(content).append("\n\n");
				break;
			}
		}
	}

	private void appendFollowUp(StringBuilder sb, JsonNode root) {
		for (JsonNode message : root.path("messages")) {
			String type = message.path("type").asText("");
			String content = message.path("content").asText("");
			if (!"follow_up".equals(type) || content.isBlank()) {
				continue;
			}
			try {
				JsonNode arr = jsonMapper.readTree(content);
				if (arr.isArray() && !arr.isEmpty()) {
					sb.append("追问问题：\n");
					int i = 1;
					for (JsonNode q : arr) {
						String text = q.asText("");
						if (!text.isBlank()) {
							sb.append(i++).append(". ").append(text).append('\n');
						}
					}
					sb.append('\n');
				}
			}
			catch (JacksonException ignored) {
				// 单条追问问题解析失败时忽略
			}
			break;
		}
	}

	/** 模态卡：source 消息且 content 为含 {@code modelCard} 的数组时，结构化 JSON 直接返回（不转文本）。 */
	private void appendModelCards(StringBuilder sb, JsonNode root) {
		for (JsonNode message : root.path("messages")) {
			String type = message.path("type").asText("");
			String contentType = message.path("content_type").asText("");
			String content = message.path("content").asText("");
			if (!"source".equals(type) || "webpage".equals(contentType) || content.isBlank()) {
				continue;
			}
			try {
				JsonNode arr = jsonMapper.readTree(content);
				if (!arr.isArray() || arr.isEmpty() || !hasModelCard(arr)) {
					continue;
				}
				sb.append("模态卡[").append(contentType).append("]：\n");
				sb.append(jsonMapper.writeValueAsString(arr)).append("\n\n");
			}
			catch (JacksonException ignored) {
				// 单条模态卡解析失败时忽略
			}
		}
	}

	private static boolean hasModelCard(JsonNode arr) {
		for (JsonNode item : arr) {
			if (item.hasNonNull("modelCard")) {
				return true;
			}
		}
		return false;
	}

	private void appendWebpageSources(StringBuilder sb, JsonNode root) {
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
			// summary=true 时返回长摘要字段，优先于 snippet 简短描述（ADR-0015：web 深度采集）
			String snippet = page.path("snippet").asText("");
			String summary = page.path("summary").asText("");
			String detail = !summary.isBlank() ? summary : snippet;

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
			if (!detail.isBlank()) {
				sb.append("   ").append(detail).append('\n');
			}
		}
	}

}
