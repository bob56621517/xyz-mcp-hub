package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * 通用转发器机制测试夹具（#52）：可编程的 {@link ProxyMcpProvider} 测试替身，替代已删除的具体
 * Provider 类（Context7 / GrepApp / Wikidata / Github）。
 *
 * <p>字段均可注入：name / upstreamUrl / authHeaders / toolNames / enabled；{@code discoverer}
 * 提供者非 {@code null} 时覆写 {@link #discoverTools()} 返回固定规格（不触网），否则走真实
 * {@code connect()} 链路（供不可达降级测试）。{@code close} 计数供释放验证。</p>
 *
 * <p>无外部依赖：不启动 Spring 上下文、不触网（除非真实 discoverTools）。</p>
 */
public class TestProxyMcpProvider extends ProxyMcpProvider {

	private final String name;
	private final String upstreamUrl;
	private final Map<String, String> authHeaders;
	private final List<String> toolNames;
	private final boolean enabled;
	private final Supplier<List<McpServerFeatures.AsyncToolSpecification>> discoverer;

	private int closeCalls = 0;

	public TestProxyMcpProvider(String name, String upstreamUrl) {
		this(name, upstreamUrl, Map.of(), List.of(), true, null);
	}

	public TestProxyMcpProvider(String name, String upstreamUrl, Map<String, String> authHeaders,
			List<String> toolNames, boolean enabled,
			Supplier<List<McpServerFeatures.AsyncToolSpecification>> discoverer) {
		this.name = name;
		this.upstreamUrl = upstreamUrl;
		this.authHeaders = authHeaders;
		this.toolNames = toolNames;
		this.enabled = enabled;
		this.discoverer = discoverer;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getUpstreamUrl() {
		return upstreamUrl;
	}

	@Override
	public Map<String, String> getAuthHeaders() {
		return authHeaders;
	}

	@Override
	public List<String> getToolNames() {
		return toolNames;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public List<McpServerFeatures.AsyncToolSpecification> discoverTools() {
		return discoverer != null ? discoverer.get() : super.discoverTools();
	}

	@Override
	public void close() {
		closeCalls++;
		// discoverer=null 走真实 connect 时，super.close() 释放已缓存的上游连接（防泄漏）
		super.close();
	}

	/** {@code close()} 被调用次数。 */
	public int closeCalls() {
		return closeCalls;
	}
}
