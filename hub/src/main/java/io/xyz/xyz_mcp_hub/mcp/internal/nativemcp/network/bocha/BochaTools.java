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
 * <p>源：name=bocha，scope=NETWORK，type 默认 NATIVE（包装 HTTP API）。缺少 {@code bocha.api-key} 时
 * {@link #isEnabled()} 返回 {@code false}（源仍已注册、目录列出 enabled=false、工具为空，#50）。</p>
 */
@Component
public class BochaTools implements McpEndpointProvider {

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

	@Tool(name = "web_search", description = "博查网页搜索：从全网检索网页结果，返回标题、链接、站点与摘要。freshness 限定时间范围，取值 noLimit / oneDay / oneWeek / oneMonth / oneYear。")
	public String webSearch(
			@ToolParam(description = "搜索关键词") String query,
			@ToolParam(required = false, description = "返回结果数量，1-50，默认 10") Integer count,
			@ToolParam(required = false, description = "时间范围，默认 noLimit") String freshness) {
		return bochaClient.webSearch(query, count, freshness);
	}

	@Tool(name = "ai_search", description = "博查 AI 搜索：在全网搜索基础上返回 AI 总结答案与参考源，适合需要综述回答的场景。freshness 取值同网页搜索。")
	public String aiSearch(
			@ToolParam(description = "搜索关键词") String query,
			@ToolParam(required = false, description = "返回结果数量，1-50，默认 10") Integer count,
			@ToolParam(required = false, description = "时间范围，默认 noLimit") String freshness) {
		return bochaClient.aiSearch(query, count, freshness);
	}

}
