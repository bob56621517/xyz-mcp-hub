package io.xyz.xyz_mcp_hub.mcp.internal.space;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SpaceDefinition;
import io.xyz.xyz_mcp_hub.mcp.SpaceDefinitionSource;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 组合端点 Space 的 MCP 端点提供者（ADR-0008）。
 *
 * <p>实现维度上不属于任何类——不自实现、不代理上游，仅引用并拼装其他已注册端点的工具。
 * 属于普通 {@link McpEndpointProvider}，走 {@code HubMcpRegistrar} 总线；工具列表在注册时
 * 物化（聚合引用端点的 {@link ToolCallback}）。未启用/不可达源跳过并告警，include 缺失工具
 * fail-fast，冲突后覆盖并告警。</p>
 */
public class SpaceMcpProvider implements McpEndpointProvider, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SpaceMcpProvider.class);

	private final SpaceDefinition definition;

	/** 引用 proxy 源时建立的到上游的连接，关闭时优雅释放。 */
	private final List<McpSyncClient> proxyClients = new ArrayList<>();

	@Autowired
	private ApplicationContext context;

	private volatile List<ToolCallback> materialized;

	public SpaceMcpProvider(List<SpaceDefinitionSource> sources, String spaceName) {
		this.definition = sources.stream()
			.flatMap(source -> source.load().stream())
			.filter(definition -> definition.name().equals(spaceName))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Space " + spaceName + " 定义不存在"));
	}

	@Override
	public String getName() {
		return definition.name();
	}

	@Override
	public String getPath() {
		return definition.effectivePath();
	}

	@Override
	public Scope getScope() {
		return Scope.NETWORK;
	}

	@Override
	public boolean isEnabled() {
		// Space 定义已存在即注册候选（配置检查）；可用性由 getTools() 物化决定。
		// 不在此发起网络/抛错（fail-fast 由 getTools() 物化触发，见 SPI 契约）。
		return true;
	}

	@Override
	public List<ToolCallback> getTools() {
		List<ToolCallback> tools = materialize();
		if (tools.isEmpty()) {
			log.warn("Space {}（{}）物化后无工具", getName(), getPath());
		}
		return tools;
	}

	private List<ToolCallback> materialize() {
		if (materialized == null) {
			materialized = SpaceToolMaterializer.materialize(definition, this::sourceTools);
		}
		return materialized;
	}

	/**
	 * 按源端点名解析其完整工具列表；源未注册 / 未启用 / proxy 连接失败时返回 {@code null}
	 * （物化器跳过该引用，延续优雅降级）。
	 */
	private List<ToolCallback> sourceTools(String sourceName) {
		Map<String, McpEndpointProvider> endpoints = context.getBeansOfType(McpEndpointProvider.class)
			.values()
			.stream()
			.collect(Collectors.toMap(McpEndpointProvider::getName, Function.identity(), (a, b) -> a));
		McpEndpointProvider provider = endpoints.get(sourceName);
		if (provider == null) {
			log.warn("Space {} 引用的源端点 {} 未注册，跳过该引用", getName(), sourceName);
			return null;
		}
		if (!provider.isEnabled()) {
			log.warn("Space {} 引用的源端点 {} 未启用，跳过该引用", getName(), sourceName);
			return null;
		}
		if (provider instanceof ProxyMcpProvider proxy) {
			try {
				McpSyncClient client = proxy.connect();
				proxyClients.add(client);
				// 应用提供者固定的工具子集（如 github-readonly 只读清单，ADR-0007 决策 3），
				// 再物化为 ToolCallback，避免把上游全量工具泄入组合端点
				List<String> selected = proxy.selectTools(client.listTools().tools())
					.stream()
					.map(McpSchema.Tool::name)
					.toList();
				if (selected.isEmpty()) {
					return List.of();
				}
				return McpToolUtils.getToolCallbacksFromSyncClients(List.of(client)).stream()
					.filter(tool -> selected.contains(tool.getToolDefinition().name()))
					.toList();
			}
			catch (RuntimeException e) {
				log.warn("Space {} 引用源端点 {} 连接失败，跳过：{}", getName(), sourceName, e.getMessage());
				return null;
			}
		}
		return provider.getTools();
	}

	@Override
	public void destroy() {
		proxyClients.forEach(McpSyncClient::closeGracefully);
		proxyClients.clear();
	}

}
