package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * 通用转发器的 hook 可扩展点（#52，ADR-0007 决策 3）：自定义认证 / 工具子集 / 工具名映射 / 错误处理。
 *
 * <p>配置驱动的 {@link ConfigProxyMcpProvider} 从配置条目构造默认 hooks（认证 header、工具子集，
 * 见 {@link #from(ProxySourceConfig)}）；特殊代理需求经 {@link ProxyHooksCustomizer} 覆盖其中若干
 * hook，**无需新增 Provider 类**（ADR-0007：不逐个写 Provider 类）。</p>
 */
public interface ProxyHooks {

	/** 自定义认证：随每个上游请求发送的固定 header；空 = 无认证。 */
	default Map<String, String> authHeaders() {
		return Map.of();
	}

	/** 自定义工具子集：需透传的上游工具名；空 = 全量透传。 */
	default List<String> toolSubset() {
		return List.of();
	}

	/** 工具名映射 hook：转发到上游前把上游工具名映射为目标调用名（默认原样）。 */
	default String mapToolName(String upstreamToolName) {
		return upstreamToolName;
	}

	/** 错误处理 hook：上游 {@code callTool} 抛异常时的统一处理（默认重新抛出，转为 MCP 调用错误）。 */
	default McpSchema.CallToolResult handleCallError(McpSchema.CallToolRequest request, RuntimeException error) {
		throw error;
	}

	/**
	 * 从配置条目构造默认 hooks：认证 header 解析（{@link #parseAuthHeader}）+ 工具子集；
	 * 工具名映射 / 错误处理走默认（原样 / 重抛）。
	 */
	static ProxyHooks from(ProxySourceConfig config) {
		return new ProxyHooks() {
			@Override
			public Map<String, String> authHeaders() {
				return parseAuthHeader(config.authHeader());
			}

			@Override
			public List<String> toolSubset() {
				return config.toolsSubset();
			}
		};
	}

	/**
	 * 解析 {@code auth-header} 配置为 header 映射：
	 * <ul>
	 *   <li>{@code "Name: Value"} 完整 header 行 → {@code {Name: Value}}（名字与值各自 trim）；</li>
	 *   <li>无冒号的裸值 → 自动补 {@code Authorization} 名（{@code "Bearer xyz"} → {@code {Authorization: Bearer xyz}}）；</li>
	 *   <li>{@code null} / 空白 → 空映射（无认证，公开代理场景）。</li>
	 * </ul>
	 */
	static Map<String, String> parseAuthHeader(String authHeader) {
		if (authHeader == null || authHeader.isBlank()) {
			return Map.of();
		}
		int colon = authHeader.indexOf(':');
		if (colon < 0) {
			return Map.of("Authorization", authHeader.trim());
		}
		String name = authHeader.substring(0, colon).trim();
		String value = authHeader.substring(colon + 1).trim();
		return name.isBlank() ? Map.of() : Map.of(name, value);
	}
}
