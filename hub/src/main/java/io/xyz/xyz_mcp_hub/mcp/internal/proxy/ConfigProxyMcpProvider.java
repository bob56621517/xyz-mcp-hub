package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * 配置驱动的 proxy 源（#52，ADR-0007 决策 2）：一个 {@code mcp.proxies} 条目 → 一个透明转发器实例。
 *
 * <p>通用转发器本体（{@link ProxyMcpProvider}：启动时 {@code listTools} 发现 + {@code callTool}
 * 透传）不变，本类把配置字段（name / upstream-url / auth-header / tools-subset / enabled）与
 * 可选的 {@link ProxyHooks} 桥接为 provider。enabled 门控由
 * {@link ProxySourceConfig#effectiveEnabled()} 决定（注册/启用分离：未启用源目录列出、
 * {@code enabled=false}、工具为空，见 ADR-0005）。</p>
 */
public class ConfigProxyMcpProvider extends ProxyMcpProvider {

	private final ProxySourceConfig config;
	private final ProxyHooks hooks;

	public ConfigProxyMcpProvider(ProxySourceConfig config) {
		this(config, ProxyHooks.from(config));
	}

	/** 自定义 hooks（特殊代理需求，不新增 Provider 类）；{@code null} 回退到配置推导。 */
	public ConfigProxyMcpProvider(ProxySourceConfig config, ProxyHooks hooks) {
		this.config = Objects.requireNonNull(config, "config");
		this.hooks = hooks == null ? ProxyHooks.from(config) : hooks;
	}

	/** 底层配置条目。 */
	public ProxySourceConfig config() {
		return config;
	}

	@Override
	public String getName() {
		return config.name();
	}

	@Override
	public String getUpstreamUrl() {
		return config.upstreamUrl();
	}

	@Override
	public Map<String, String> getAuthHeaders() {
		return hooks.authHeaders();
	}

	@Override
	public List<String> getToolNames() {
		return hooks.toolSubset();
	}

	@Override
	public String mapToolName(String upstreamToolName) {
		return hooks.mapToolName(upstreamToolName);
	}

	@Override
	public McpSchema.CallToolResult handleCallError(McpSchema.CallToolRequest request, RuntimeException error) {
		return hooks.handleCallError(request, error);
	}

	@Override
	public boolean isEnabled() {
		return config.effectiveEnabled();
	}
}
