package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import java.util.List;

import io.xyz.xyz_mcp_hub.bocha.BochaClient;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * bocha 源的工具类即源（#53）：{@link BochaTools} 本身实现 {@link McpEndpointProvider}——{@code @Tool}
 * 方法 + 源元数据（getName/scope/enabled/getTools）合一，纯能力由顶级模块 {@link BochaClient} 承担。
 * 可 {@code new BochaTools(...)} 直接调用 {@code @Tool} 方法测试（不再有 {@code BochaMcpProvider} 包装类）。
 *
 * <p>AI 习惯层（ADR-0015）：原 web_search / ai_search 两工具**合成为单个 {@code search} 工具**（暴露为
 * {@code bocha_search}），{@code type} 默认 {@code "ai"} 路由 web/ai——工具层消化官网复杂度（count 默认
 * 20、summary web=true/ai=false 内部策略化、ai answer=true），描述引导模型按分工使用。</p>
 *
 * <p>源：name=bocha，scope=NETWORK，type 默认 NATIVE（包装 HTTP API）。缺少 {@code bocha.api-key} 时
 * {@link #isEnabled()} 返回 {@code false}（源仍已注册、目录列出 enabled=false、工具为空，#50）。</p>
 */
@Component
public class BochaTools implements McpEndpointProvider {

	/** count 默认：返回条数上限（ADR-0015，统一 20，不按 type 分）。 */
	private static final int DEFAULT_COUNT = 20;
	/** AI Search 的 answer 恒 true（返回总结答案+追问问题）；Web Search 的 summary 恒 true（长摘要）。 */
	private static final boolean DEFAULT_SUMMARY = true;
	private static final boolean DEFAULT_ANSWER = true;

	private final BochaClient bochaClient;
	private final String apiKey;
	private final List<ToolCallback> tools;

	public BochaTools(BochaClient bochaClient, @Value("${bocha.api-key:}") String apiKey) {
		this.bochaClient = bochaClient;
		this.apiKey = apiKey;
		this.tools = List.of(MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks());
	}

	@Override
	public String getName() {
		return "bocha";
	}

	@Override
	public Scope getScope() {
		return Scope.NETWORK;
	}

	@Override
	public boolean isEnabled() {
		return apiKey != null && !apiKey.isBlank();
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

	@Tool(name = "search", description = """
		博查联网搜索，用户主动加入的联网工具。

		默认走 AI 语义搜索：理解意图、纠正错误前提，返回总结答案、追问问题、参考来源 + 结构化模态卡
		（天气/百科/汇率/股票等），适合首次搜索、事实核查、垂域查询。需要深度调研、多角度对比、枚举大量
		条目、指定网站范围时，设 type="web" 走纯网页检索，返回长摘要网页列表。

		可按 count 控制返回量（默认 20，最高 50）；freshness 限定时间范围（默认 noLimit）；
		include / exclude 限定或排除网站范围。

		提示：返回的网页来源（type=ai 的参考来源 / type=web 的网页结果），请在最终回答末尾将 URL 渲染为
		超链接附上。
		""")
	public String search(
			@ToolParam(required = false, description = "搜索类型：ai=AI 语义搜索（默认，返回总结答案+追问问题+参考来源+模态卡）；web=纯网页检索（返回长摘要网页列表，适合深度调研/多角度/枚举/指定网站）") String type,
			@ToolParam(description = "搜索关键词") String query,
			@ToolParam(required = false, description = "返回结果数量上限，1-50，默认 20") Integer count,
			@ToolParam(required = false, description = "时间范围，默认 noLimit；可选 oneDay/oneWeek/oneMonth/oneYear") String freshness,
			@ToolParam(required = false, description = "限定网站范围，多个域名用 | 或 , 分隔，最多 100 个") String include,
			@ToolParam(required = false, description = "排除网站范围，多个域名用 | 或 , 分隔，最多 100 个（仅 type=web 生效）") String exclude) {
		boolean web = "web".equalsIgnoreCase(type);
		int n = count == null ? DEFAULT_COUNT : count;
		if (web) {
			// web：透传 include/exclude（官网支持）；summary 恒 true 长摘要
			return bochaClient.webSearch(query, n, freshness, DEFAULT_SUMMARY, include, exclude);
		}
		// ai（默认）：answer 恒 true；exclude 官网不支持 → 忽略不传；include 透传
		return bochaClient.aiSearch(query, n, freshness, include, DEFAULT_ANSWER);
	}

}
