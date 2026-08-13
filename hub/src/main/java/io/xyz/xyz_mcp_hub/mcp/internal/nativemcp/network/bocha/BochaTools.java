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
 * <p>工具层（AI 习惯层，#63）：原 web_search / ai_search 两个工具**合成为一个 {@code search} 工具**
 * （暴露名 {@code bocha_search}，逻辑名 {@code search}，源注册表加 {@code bocha_} 前缀）。{@code type}
 * 默认 {@code "ai"} 路由 AI 语义搜索（一次答对），需要深度/多角度/枚举/指定网站时显式
 * {@code type="web"}。官网复杂度（count 默认 20、summary/answer 内部策略化）在此消化，描述文案引导
 * 模型按默认走 AI 语义搜索。</p>
 *
 * <p>源：name=bocha，scope=NETWORK，type 默认 NATIVE（包装 HTTP API）。缺少 {@code bocha.api-key} 时
 * {@link #isEnabled()} 返回 {@code false}（源仍已注册、目录列出 enabled=false、工具为空，#50）。</p>
 */
@Component
public class BochaTools implements McpEndpointProvider {

	/** 工具层消化官网复杂度（#63 决策 9）：count 默认 20（#63 §4.2 饱和点）。 */
	private static final int DEFAULT_COUNT = 20;

	/** 工具层消化官网复杂度（#63 决策 9）：freshness 默认 noLimit。 */
	private static final String DEFAULT_FRESHNESS = "noLimit";

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
			博查联网搜索，用户主动加入的联网工具。默认（type 缺省为 ai）走 AI 语义搜索：一次调用即返回
			总结答案、追问问题、参考来源与结构化模态卡（天气/百科/医疗/火车等垂域数据），适合事实问答与
			综述；需要深度调研、多角度对比、枚举大量条目或指定网站范围时，显式设 type="web" 走网页搜索，
			返回长摘要网页列表，便于多来源交叉验证。
			count 为返回条数上限（默认 20，最大 50）；freshness 限定时效（noLimit/oneDay/oneWeek/oneMonth/
			oneYear 或 YYYY-MM-DD..YYYY-MM-DD 日期范围，默认 noLimit）；include/exclude 限定或排除网站
			范围（域名用 | 或 , 分隔，最多 100 个；exclude 仅 type="web" 生效）。
			返回的网页来源请在回答末尾把 URL 渲染为超链接附上，便于用户溯源。""")
	public String search(
			@ToolParam(required = false, description = "搜索类型：ai（默认，AI 语义搜索，返回总结答案+追问问题+模态卡）或 web（网页搜索，返回长摘要网页列表）") String type,
			@ToolParam(description = "搜索关键词") String query,
			@ToolParam(required = false, description = "返回条数上限，默认 20，最大 50") Integer count,
			@ToolParam(required = false, description = "时效范围：noLimit/oneDay/oneWeek/oneMonth/oneYear 或 YYYY-MM-DD..YYYY-MM-DD，默认 noLimit") String freshness,
			@ToolParam(required = false, description = "限定网站范围，域名用 | 或 , 分隔，最多 100 个") String include,
			@ToolParam(required = false, description = "排除网站范围，域名用 | 或 , 分隔，最多 100 个（仅 type=\"web\" 生效）") String exclude) {
		// 工具层消化默认值（#63 决策 9）：count 缺省 20、freshness 缺省 noLimit，再透传能力层
		int n = count == null ? DEFAULT_COUNT : count;
		String fresh = (freshness == null || freshness.isBlank()) ? DEFAULT_FRESHNESS : freshness;
		if ("web".equalsIgnoreCase(type)) {
			return bochaClient.webSearch(query, n, fresh, include, exclude);
		}
		// 默认 ai；AI 无 exclude 参数（官网缺口），忽略 exclude
		return bochaClient.aiSearch(query, n, fresh, include);
	}

}
