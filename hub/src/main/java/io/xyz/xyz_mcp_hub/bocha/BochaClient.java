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
 * HTTP API，返回格式化搜索结果文本。
 *
 * <p>零 MCP/Spring AI 依赖（仅用 spring-web 的 {@link RestClient} 与 jackson），可独立复用与测试。
 * base-url 由配置键 {@code bocha.url} 注入（默认 {@code https://api.bochaai.com}；官方飞书文档为
 * {@code api.bocha.cn}，见 {@code BochaConfig}），路径 {@code /v1/web-search}（网页搜索）与
 * {@code /v1/ai-search}（AI 搜索），请求头 {@code Authorization: Bearer <api-key>}。</p>
 *
 * <p>忠于官网（#63 能力层）：web 带 {@code summary=true}（长摘要）与 {@code include}/{@code exclude}
 * 网站范围透传；ai 带 {@code answer=true}（总结答案 + 追问问题）与 {@code include} 透传（AI 无
 * {@code exclude} 参数，忽略）；count 透传、默认 20；freshness 支持枚举与日期范围。</p>
 */
public class BochaClient {

	private static final int DEFAULT_COUNT = 20;
	private static final int MAX_COUNT = 50;
	private static final String DEFAULT_FRESHNESS = "noLimit";
	private static final List<String> FRESHNESS_VALUES = List.of(
			"noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear");
	/** 官网 freshness 日期范围/指定日期格式（YYYY-MM-DD..YYYY-MM-DD 或 YYYY-MM-DD）。 */
	private static final Pattern FRESHNESS_DATE_RANGE = Pattern.compile(
			"\\d{4}-\\d{2}-\\d{2}(\\.\\.\\d{4}-\\d{2}-\\d{2})?");

	private final RestClient restClient;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public BochaClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * 网页搜索（type=web）：从全网检索网页结果，返回标题、链接、站点与长摘要。
	 *
	 * @param query 搜索关键词
	 * @param count 返回条数上限（null → 20，clamp 1..50）
	 * @param freshness 时效范围（枚举或日期范围，null → noLimit）
	 * @param include 限定网站范围（域名用 | 或 , 分隔，最多 100 个；null 不传）
	 * @param exclude 排除网站范围（同上；null 不传）
	 */
	public String webSearch(String query, Integer count, String freshness, String include, String exclude) {
		return search("web-search", query, count, freshness, include, exclude, true, null);
	}

	/**
	 * AI 搜索（type=ai）：在全网搜索基础上返回 AI 总结答案、追问问题、参考来源与结构化模态卡。
	 *
	 * @param query 搜索关键词
	 * @param count 返回条数上限（null → 20，clamp 1..50）
	 * @param freshness 时效范围（枚举或日期范围，null → noLimit）
	 * @param include 限定网站范围（域名用 | 或 , 分隔，最多 100 个；null 不传。AI 无 exclude 参数）
	 */
	public String aiSearch(String query, Integer count, String freshness, String include) {
		return search("ai-search", query, count, freshness, include, null, null, true);
	}

	private String search(String endpoint, String query, Integer count, String freshness,
			String include, String exclude, Boolean summary, Boolean answer) {
		if (query == null || query.isBlank()) {
			return "请提供搜索关键词 query。";
		}
		int n = count == null ? DEFAULT_COUNT : Math.max(1, Math.min(count, MAX_COUNT));
		String fresh = normalizeFreshness(freshness);

		Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put("query", query);
		requestBody.put("count", n);
		requestBody.put("freshness", fresh);
		if (Boolean.TRUE.equals(summary)) {
			requestBody.put("summary", true);
		}
		if (Boolean.TRUE.equals(answer)) {
			requestBody.put("answer", true);
		}
		if (include != null && !include.isBlank()) {
			requestBody.put("include", include);
		}
		if (exclude != null && !exclude.isBlank()) {
			requestBody.put("exclude", exclude);
		}

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

	/** freshness：枚举原样透传；日期范围/指定日期透传；空或未知 → noLimit。 */
	private String normalizeFreshness(String freshness) {
		if (freshness == null || freshness.isBlank()) {
			return DEFAULT_FRESHNESS;
		}
		if (FRESHNESS_VALUES.contains(freshness) || FRESHNESS_DATE_RANGE.matcher(freshness).matches()) {
			return freshness;
		}
		return DEFAULT_FRESHNESS;
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
	 * AI 总结；{@code type=source} 的 content 为 JSON encode 字符串——{@code content_type=webpage}
	 * 含 {@code value[]} 网页列表（字段与 web-search 的 webPages.value 一致）；其余 content_type 均为
	 * **模态卡**（多模态参考源，已知 weather_china / baike_pro / medical_common / douyin / calendar /
	 * train_line 等，官网仍在扩展），content 为 JSON 数组字符串、数组项含 {@code modelCard} 结构化卡
	 * （JSON 原样返回）；{@code type=follow_up} 的消息 content 为 JSON 数组字符串（追问问题列表）。
	 * 模态卡不设白名单——非 webpage 的 source 一律尝试解析 modelCard，新增卡类型不静默丢弃。
	 */
	private void appendAiSearch(StringBuilder sb, JsonNode root) {
		JsonNode messages = root.path("messages");
		for (JsonNode message : messages) {
			String type = message.path("type").asText("");
			String content = message.path("content").asText("");
			if ("answer".equals(type) && !content.isBlank()) {
				sb.append("AI 总结：").append(content).append("\n\n");
				break;
			}
		}
		for (JsonNode message : messages) {
			String type = message.path("type").asText("");
			String contentType = message.path("content_type").asText("");
			String content = message.path("content").asText("");
			if (!"source".equals(type) || content.isBlank()) {
				continue;
			}
			if ("webpage".equals(contentType)) {
				try {
					appendPages(sb, jsonMapper.readTree(content).path("value"));
				}
				catch (JacksonException ignored) {
					// 单条 source 解析失败时忽略，继续后续消息
				}
			}
			else {
				// 模态卡（weather_china/baike_pro/…，含未来新增类型）：content 为 JSON 数组、数组项含
				// modelCard。白名单外类型也尝试解析，不静默丢弃新增卡；无 modelCard 时自然跳过
				appendModelCard(sb, contentType, content);
			}
		}
		for (JsonNode message : messages) {
			String type = message.path("type").asText("");
			String content = message.path("content").asText("");
			if ("follow_up".equals(type) && !content.isBlank()) {
				appendFollowUp(sb, content);
				break;
			}
		}
	}

	/**
	 * 模态卡：content 为 JSON 数组字符串，数组项含 {@code modelCard} 字段——结构化 JSON 原样返回
	 * （#63 决策 7：最终由 LLM 消费，JSON 紧凑保真，不转自然语言）。
	 */
	private void appendModelCard(StringBuilder sb, String contentType, String content) {
		try {
			JsonNode array = jsonMapper.readTree(content);
			for (JsonNode item : array.isArray() ? array : array.path("value")) {
				JsonNode card = item.path("modelCard");
				if (!card.isMissingNode() && !card.isNull()) {
					sb.append("模态卡 · ").append(contentType).append("：\n")
						.append(card.toString()).append('\n');
				}
			}
		}
		catch (JacksonException ignored) {
			// 单条模态卡解析失败时忽略，继续后续消息
		}
	}

	/** 追问问题：content 为 JSON 数组字符串，呈现为问题列表。 */
	private void appendFollowUp(StringBuilder sb, String content) {
		try {
			JsonNode array = jsonMapper.readTree(content);
			if (!array.isArray() || array.isEmpty()) {
				return;
			}
			sb.append("追问问题：\n");
			int i = 1;
			for (JsonNode question : array) {
				String text = question.isTextual() ? question.asText() : question.toString();
				if (!text.isBlank()) {
					sb.append(i++).append(". ").append(text).append('\n');
				}
			}
		}
		catch (JacksonException ignored) {
			// 追问问题解析失败时忽略
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
