package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.mcp.internal.containermcp.ContainerMcp;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;

/**
 * 源注册表（ADR-0011）：收集所有 {@link McpEndpointProvider}，把「provider 演化为 source」，为单
 * McpServer 提供统一的工具注册与 URL 参数工具视图解析。
 *
 * <p>原生源（{@link NativeMcp}）工具来自代码声明（{@link #getTools()}）；#35 起 proxy 源
 * （{@link ProxyMcpProvider}）一并迁入——工具清单启动时向上游 {@code listTools} 发现并缓存（公有云
 * 上游不受控，见工具清单来源规则）；#37 起容器源（{@link ContainerMcp}，如 markitdown）迁入——工具为
 * 静态冒烟清单。工具名统一加 {@code {source}_} 前缀保证跨源全局唯一（MCP 工具名规范不允许点，暴露名
 * 与语法名同一套体系，零映射）。</p>
 *
 * <p>源降级（沿用 {@link McpEndpointProvider#isEnabled()} 语义）：proxy 上游不可达（连接/握手/
 * listTools 失败）时该源不入注册表、应用照常启动，不拖垮启动（#35）；容器源 docker 运行时缺失/清单缺
 * 规格时 isEnabled=false 不入注册表（#37）。</p>
 *
 * <p>每个源携带目录元数据（{@link McpSource}：type / protocol / scope / base，issue #34），供目录
 * API（{@link CatalogEndpoint}）读取；proxy / container 源迁入后目录自动增长。</p>
 *
 * <p>解析规则（与 ADR-0011 完全一致）：先精确匹配工具名，再按源名展开该源全部工具；未知项静默
 * 忽略 + 日志 warn；无参数 = 全量。</p>
 */
public class McpSourceRegistry {

	private static final Logger log = LoggerFactory.getLogger(McpSourceRegistry.class);

	/**
	 * 单个源：源名 + 目录元数据（type/protocol/scope/base，ADR-0011）+ 底层 provider + 带前缀的工具规格。
	 *
	 * <p>元数据模型（#34）为 #35/#36/#37 共用、一次定稿：{@code type} 由 provider 声明
	 * （{@link McpEndpointProvider#getSourceType()}）；{@code protocol} 为 container 专有（mcp|rest，
	 * 非容器源为 null）；{@code scope} 取 provider 部署范围；{@code base} 为组合源溯源（#33 组合源
	 * 构建器合入后填充，非组合源为 null）。</p>
	 *
	 * @param name 源名（URL includes/excludes 引用单元）
	 * @param type 源类型（native/proxy/container/host/composite）
	 * @param protocol 容器接入协议（container 专有，其余为 null）
	 * @param scope 部署范围（host/network）
	 * @param provider 底层端点提供者
	 * @param specs 带 {@code {source}_} 前缀的工具规格（全量注册）
	 * @param base 组合源溯源（非组合源为 null，#33 后填充）
	 */
	public record McpSource(String name, SourceType type, Protocol protocol, Scope scope,
			McpEndpointProvider provider, List<McpServerFeatures.AsyncToolSpecification> specs,
			CompositeBase base) {
	}

	private final List<McpSource> sources;

	/** 带前缀工具名 → 工具规格（全量注册）。 */
	private final Map<String, McpServerFeatures.AsyncToolSpecification> specsByName;

	/** 源名 → 源（用于源名展开）。 */
	private final Map<String, McpSource> sourcesByName;

	public McpSourceRegistry(List<McpEndpointProvider> providers) {
		this.sources = providers.stream()
			// #35 起接纳 ProxyMcp（proxy 工具清单启动时发现）；#37 再迁入 ContainerMcp（容器源）
			.filter(provider -> provider instanceof NativeMcp
				|| provider instanceof ProxyMcpProvider || provider instanceof ContainerMcp)
			.filter(McpEndpointProvider::isEnabled)
			.map(this::toSource)
			.filter(Objects::nonNull)
			.toList();
		this.sourcesByName = sources.stream()
			.collect(Collectors.toMap(McpSource::name, Function.identity(), (a, b) -> a));
		this.specsByName = sources.stream()
			.flatMap(source -> source.specs().stream())
			.collect(Collectors.toMap(spec -> spec.tool().name(), Function.identity(), (a, b) -> {
				log.warn("工具名 {} 由多个源提供，保留先注册者", a.tool().name());
				return a;
			}));
	}

	/** 全部已注册源。 */
	public List<McpSource> sources() {
		return sources;
	}

	/** 全部已注册工具规格（全量视图）。 */
	public List<McpServerFeatures.AsyncToolSpecification> allSpecs() {
		return List.copyOf(specsByName.values());
	}

	/** 全部已注册工具名。 */
	public Set<String> allToolNames() {
		return specsByName.keySet();
	}

	/** 按带前缀工具名查规格。 */
	public Optional<McpServerFeatures.AsyncToolSpecification> specByName(String toolName) {
		return Optional.ofNullable(specsByName.get(toolName));
	}

	/**
	 * 解析 URL 参数过滤器，返回可见工具名集合。
	 *
	 * <p>{@code includes} 先选（并集，空 = 全量），{@code excludes} 再减；无参数 = 全量；未知项
	 * 静默忽略 + warn。</p>
	 */
	public Set<String> visibleToolNames(ToolFilter filter) {
		Set<String> all = allToolNames();
		if (filter == null || filter.isEmpty()) {
			return all;
		}
		Set<String> selected = new LinkedHashSet<>();
		if (filter.includes().isEmpty()) {
			selected.addAll(all);
		}
		else {
			for (String item : filter.includes()) {
				applyItem(item, selected, true);
			}
		}
		for (String item : filter.excludes()) {
			applyItem(item, selected, false);
		}
		return selected;
	}

	/** 工具是否在过滤器给定的视图中可见。 */
	public boolean isVisible(String toolName, ToolFilter filter) {
		return visibleToolNames(filter).contains(toolName);
	}

	/**
	 * 解析单项：先精确匹配工具名，再按源名展开（{@code {source}_} 前缀匹配该源全部工具）；未知项
	 * 静默忽略 + 日志 warn。
	 */
	private void applyItem(String item, Set<String> target, boolean add) {
		if (specsByName.containsKey(item)) {
			if (add) {
				target.add(item);
			}
			else {
				target.remove(item);
			}
			return;
		}
		McpSource source = sourcesByName.get(item);
		if (source != null) {
			String prefix = McpToolUtils.format(source.name()) + "_";
			for (String name : specsByName.keySet()) {
				if (name.startsWith(prefix)) {
					if (add) {
						target.add(name);
					}
					else {
						target.remove(name);
					}
				}
			}
			return;
		}
		log.warn("工具视图过滤：未知的 includes/excludes 项被忽略: {}", item);
	}

	/**
	 * 释放源注册表持有的资源：proxy 源的上游连接（#35，{@code McpSingleServer.close()} 时调用）。
	 * 重复调用安全。
	 */
	public void close() {
		sources.stream()
			.map(McpSource::provider)
			.filter(ProxyMcpProvider.class::isInstance)
			.map(ProxyMcpProvider.class::cast)
			.forEach(ProxyMcpProvider::close);
	}

	/** 把一个 provider 化为 source：工具名加 {@code {source}_} 前缀；目录元数据取自 provider 声明。 */
	private McpSource toSource(McpEndpointProvider provider) {
		String sourceName = provider.getName();
		if (provider instanceof ProxyMcpProvider proxy) {
			// #35 ProxyMcp 迁移：proxy 源工具来自上游 listTools（启动时发现），适配为 AsyncToolSpecification
			return toProxySource(sourceName, proxy);
		}
		List<McpServerFeatures.AsyncToolSpecification> specs = provider.getTools().stream()
			.map(toolCallback -> toPrefixedSpec(sourceName, toolCallback))
			.toList();
		// #34 目录元数据：type 由 provider 声明；protocol 仅容器源有（mcp|rest，取 provider 声明，见
		// ContainerMcp#getProtocol，#37/#38；isEnabled 已保证规格存在，getProtocol 不抛）；
		// scope 取 provider 部署范围；base 为组合源溯源（#33 后填充，非组合源恒 null）
		Protocol protocol = provider instanceof ContainerMcp containerMcp ? containerMcp.getProtocol() : null;
		return new McpSource(sourceName, provider.getSourceType(), protocol, provider.getScope(), provider, specs, null);
	}

	/**
	 * proxy 源：启动时向上游 {@code listTools} 发现工具并缓存，工具名加 {@code {source}_} 前缀；
	 * 目录元数据与原生源一致取 provider 声明（type=PROXY，见 #34/#35）。上游不可达时返回
	 * {@code null}（源降级：不入注册表、不拖垮启动，沿用 {@code isEnabled()} 语义）。
	 */
	private McpSource toProxySource(String sourceName, ProxyMcpProvider proxy) {
		try {
			List<McpServerFeatures.AsyncToolSpecification> specs = proxy.discoverTools().stream()
				.map(spec -> renamePrefixed(sourceName, spec))
				.toList();
			return new McpSource(sourceName, proxy.getSourceType(), null, proxy.getScope(), proxy, specs, null);
		}
		catch (RuntimeException e) {
			log.error("proxy 源 {} 启动发现失败，源降级（不入注册表）: {}", sourceName, e.getMessage());
			return null;
		}
	}

	private McpServerFeatures.AsyncToolSpecification toPrefixedSpec(String sourceName, ToolCallback toolCallback) {
		return renamePrefixed(sourceName, McpToolUtils.toAsyncToolSpecification(toolCallback));
	}

	/** 工具规格改名加 {@code {source}_} 前缀（原 name → prefixed name，description/inputSchema/callHandler 原样）。 */
	private McpServerFeatures.AsyncToolSpecification renamePrefixed(String sourceName,
			McpServerFeatures.AsyncToolSpecification spec) {
		String prefixed = prefixedToolName(sourceName, spec.tool().name());
		McpSchema.Tool renamed = McpSchema.Tool.builder()
			.name(prefixed)
			.description(spec.tool().description())
			.inputSchema(spec.tool().inputSchema())
			.build();
		return new McpServerFeatures.AsyncToolSpecification(renamed, spec.callHandler());
	}

	/** 源名 + 工具名 → 带前缀的平坦工具名（源名归一化为下划线）。 */
	private static String prefixedToolName(String sourceName, String toolName) {
		return McpToolUtils.format(sourceName) + "_" + toolName;
	}

}
