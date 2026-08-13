package io.xyz.xyz_mcp_hub.bocha;

import java.util.ArrayList;
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
 * HTTP API，**返回结构化 VO、不格式化文本**（#63 能力层忠于官网：参数透传、默认值由工具层预设）。
 *
 * <p>零 MCP/Spring AI 依赖（仅用 spring-web 的 {@link RestClient} 与 jackson），可独立复用与测试。
 * base-url 由配置键 {@code bocha.url} 注入（默认 {@code https://api.bochaai.com}；官方飞书文档为
 * {@code api.bocha.cn}，见 {@code BochaConfig}），路径 {@code /v1/web-search}（网页搜索）与
 * {@code /v1/ai-search}（AI 搜索），请求头 {@code Authorization: Bearer <api-key>}。</p>
 *
 * <p>调用方（{@code BochaTools}）负责：预设 {@code summary}/{@code answer} 真值与 {@code count}/
 * {@code freshness} 默认值、把 VO 格式化。本类对 {@code summary}/{@code answer}/{@code exclude} 等
 * 一律透传——调用方传什么就请求什么（AI 端 exclude 实测被忽略，由调用方决定不传）。</p>
 */
public class BochaClient {

	/** 官网 count 上限（超过 clamp 到该值）。 */
	private static final int MAX_COUNT = 50;
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
	 * 网页搜索（type=web）：从全网检索网页结果（便捷重载，选填全省略走官网默认）。
	 *
	 * @param query 搜索关键词（必填）
	 * @return 网页结果列表（空列表表示无结果）
	 */
	public List<WebPage> webSearch(String query) {
		return webSearch(query, null, null, null, null, null);
	}

	/**
	 * 网页搜索（type=web）：从全网检索网页结果。
	 *
	 * @param query 搜索关键词（必填）
	 * @param count 返回条数上限（clamp 1..MAX_COUNT；null 不传，交官网默认）
	 * @param freshness 时效范围（枚举或日期范围；null 不传）
	 * @param summary 是否显示长摘要（null 不传交官网默认 false；true/false 显式覆盖；web 场景调用方传 true）
	 * @param include 限定网站范围（域名用 | 或 , 分隔，最多 100 个；null 不传）
	 * @param exclude 排除网站范围（同上；null 不传）
	 * @return 网页结果列表（空列表表示无结果）
	 * @throws IllegalStateException query 为空 / 响应非 200 / 响应无法解析
	 */
	public List<WebPage> webSearch(String query, Integer count, String freshness,
			Boolean summary, String include, String exclude) {
		return searchWeb(query, count, freshness, summary, include, exclude);
	}

	/**
	 * AI 搜索（type=ai）：在全网搜索基础上返回 AI 总结、追问问题、参考来源与模态卡（便捷重载，
	 * 选填全省略走官网默认）。
	 *
	 * @param query 搜索关键词（必填）
	 * @return AI 搜索结果（含总结/网页/模态卡/追问，可为空字段）
	 */
	public AiSearchResult aiSearch(String query) {
		return aiSearch(query, null, null, null, null);
	}

	/**
	 * AI 搜索（type=ai）：在全网搜索基础上返回 AI 总结、追问问题、参考来源与模态卡。
	 *
	 * @param query 搜索关键词（必填）
	 * @param count 返回条数上限（clamp 1..MAX_COUNT；null 不传，交官网默认）
	 * @param freshness 时效范围（枚举或日期范围；null 不传）
	 * @param answer 是否返回 AI 总结答案与追问问题（null 不传交官网默认 true；true/false 显式覆盖；ai 场景调用方传 true）
	 * @param include 限定网站范围（域名用 | 或 , 分隔，最多 100 个；null 不传。AI 无 exclude 参数）
	 * @return AI 搜索结果（含总结/网页/模态卡/追问，可为空字段）
	 * @throws IllegalStateException query 为空 / 响应非 200 / 响应无法解析
	 */
	public AiSearchResult aiSearch(String query, Integer count, String freshness,
			Boolean answer, String include) {
		return searchAi(query, count, freshness, answer, include);
	}

	// ---- web-search ----

	private List<WebPage> searchWeb(String query, Integer count, String freshness,
			Boolean summary, String include, String exclude) {
		Map<String, Object> body = requestBody(query, count, freshness);
		putIfNonNull(body, "summary", summary);
		putIfNotBlank(body, "include", include);
		putIfNotBlank(body, "exclude", exclude);

		JsonNode root = call("/v1/web-search", body);
		return parsePages(root.path("data").path("webPages").path("value"));
	}

	// ---- ai-search ----

	private AiSearchResult searchAi(String query, Integer count, String freshness,
			Boolean answer, String include) {
		Map<String, Object> body = requestBody(query, count, freshness);
		putIfNonNull(body, "answer", answer);
		putIfNotBlank(body, "include", include);

		JsonNode root = call("/v1/ai-search", body);
		return parseAiResult(root);
	}

	/** 公共请求体：query 校验 + count/freshness 透传（null 不传，交官网默认）。 */
	private Map<String, Object> requestBody(String query, Integer count, String freshness) {
		if (query == null || query.isBlank()) {
			throw new IllegalStateException("请提供搜索关键词 query。");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", query);
		if (count != null) {
			body.put("count", Math.max(1, Math.min(count, MAX_COUNT)));
		}
		String fresh = normalizeFreshness(freshness);
		if (fresh != null) {
			body.put("freshness", fresh);
		}
		return body;
	}

	/** freshness：枚举原样透传；日期范围/指定日期透传；null 不传；未知值 → noLimit。 */
	private String normalizeFreshness(String freshness) {
		if (freshness == null || freshness.isBlank()) {
			return null;
		}
		if (FRESHNESS_VALUES.contains(freshness) || FRESHNESS_DATE_RANGE.matcher(freshness).matches()) {
			return freshness;
		}
		return "noLimit";
	}

	private static void putIfNotBlank(Map<String, Object> body, String key, String value) {
		if (value != null && !value.isBlank()) {
			body.put(key, value);
		}
	}

	/** summary/answer 等布尔参数：null 不传（交官网默认），true/false 显式覆盖。 */
	private static void putIfNonNull(Map<String, Object> body, String key, Boolean value) {
		if (value != null) {
			body.put(key, value);
		}
	}

	/** 发起 POST 并返回根 JsonNode；非 200 或无法解析抛异常（含可读 msg）。 */
	private JsonNode call(String path, Map<String, Object> body) {
		String requestJson;
		try {
			requestJson = jsonMapper.writeValueAsString(body);
		} catch (JacksonException e) {
			throw new IllegalStateException("无法序列化博查请求参数", e);
		}

		String responseBody = restClient.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_JSON)
			.body(requestJson)
			.retrieve()
			.body(String.class);

		JsonNode root;
		try {
			root = jsonMapper.readTree(responseBody);
		} catch (JacksonException e) {
			throw new IllegalStateException("博查搜索失败：无法解析响应\n" + responseBody, e);
		}

		int code = root.path("code").asInt(-1);
		if (code != 200) {
			String msg = root.path("msg").asText("");
			if (msg.isBlank()) {
				msg = root.path("message").asText("");
			}
			throw new IllegalStateException("博查搜索失败（code=" + code + "）：" + msg);
		}
		return root;
	}

	// ---- 响应解析为 VO ----

	private List<WebPage> parsePages(JsonNode pages) {
		List<WebPage> result = new ArrayList<>();
		if (!pages.isArray()) {
			return result;
		}
		for (JsonNode page : pages) {
			result.add(new WebPage(
					page.path("name").asText(""),
					page.path("url").asText(""),
					page.path("siteName").asText(""),
					page.path("snippet").asText(""),
					page.path("summary").asText("")));
		}
		return result;
	}

	/**
	 * ai-search 响应：顶层 {@code messages[]}。{@code type=answer} 为 AI 总结；{@code type=source}
	 * 的 content 为 JSON encode 字符串——{@code content_type=webpage} 含 {@code value[]} 网页列表，
	 * 其余 content_type 为模态卡（weather_china/baike_pro/…，数组项含 {@code modelCard}）；
	 * {@code type=follow_up} 的 content 为 JSON 数组字符串（追问问题）。
	 */
	private AiSearchResult parseAiResult(JsonNode root) {
		String summary = null;
		List<WebPage> pages = new ArrayList<>();
		List<ModalCard> modalCards = new ArrayList<>();
		List<String> followUps = new ArrayList<>();

		for (JsonNode message : root.path("messages")) {
			String type = message.path("type").asText("");
			String contentType = message.path("content_type").asText("");
			String content = message.path("content").asText("");

			if ("answer".equals(type) && summary == null && !content.isBlank()) {
				summary = content;
			}
			else if ("source".equals(type) && !content.isBlank()) {
				if ("webpage".equals(contentType)) {
					pages.addAll(parsePages(readContent(content).path("value")));
				}
				else {
					parseModalCards(contentType, content, modalCards);
				}
			}
			else if ("follow_up".equals(type) && !content.isBlank()) {
				followUps.addAll(parseFollowUps(content));
			}
		}
		return new AiSearchResult(summary, pages, modalCards, followUps);
	}

	private JsonNode readContent(String content) {
		try {
			return jsonMapper.readTree(content);
		} catch (JacksonException ignored) {
			// 单条 source 解析失败时忽略（返回空节点，调用方自然跳过）
			return jsonMapper.getNodeFactory().objectNode();
		}
	}

	/** 模态卡：content 为 JSON 数组字符串，数组项含 modelCard——结构化 JSON 原样保留，工具层呈现。 */
	private void parseModalCards(String contentType, String content, List<ModalCard> out) {
		JsonNode array = readContent(content);
		for (JsonNode item : array.isArray() ? array : array.path("value")) {
			JsonNode card = item.path("modelCard");
			if (!card.isMissingNode() && !card.isNull()) {
				out.add(new ModalCard(contentType, card.toString()));
			}
		}
	}

	/** 追问问题：content 为 JSON 数组字符串，转问题列表。 */
	private List<String> parseFollowUps(String content) {
		List<String> result = new ArrayList<>();
		JsonNode array = readContent(content);
		if (!array.isArray()) {
			return result;
		}
		for (JsonNode question : array) {
			String text = question.isTextual() ? question.asText() : question.toString();
			if (!text.isBlank()) {
				result.add(text);
			}
		}
		return result;
	}

}
