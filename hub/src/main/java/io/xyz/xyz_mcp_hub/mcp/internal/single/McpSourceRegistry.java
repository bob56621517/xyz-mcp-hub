package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>#33 起组合源（{@code mcp.specs} YAML）一并发布入注册表：每个 spec 启动时静态解析（可嵌套、
 * 循环定义被拒并 fail-fast）为派生源，目录元数据 {@code type=composite}、{@code base} 携带过滤溯源；
 * 被 {@code includes} / {@code excludes} 引用时与普通源等效（展开其解析出的工具）。组合源不新增工具
 * 名——其工具规格复用底层普通源的同名规格（同一 call handler），故不入 {@code specsByName}。</p>
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
	 * 非容器源为 null）；{@code scope} 取 provider 部署范围（组合源派生自其聚合工具的部署范围）；
	 * {@code base} 为组合源溯源（#33 起组合源填充，非组合源为 null）。</p>
	 *
	 * @param name 源名（URL includes/excludes 引用单元）
	 * @param type 源类型（native/proxy/container/host/composite）
	 * @param protocol 容器接入协议（container 专有，其余为 null）
	 * @param scope 部署范围（host/network）
	 * @param provider 底层端点提供者（组合源为 null——组合源不实现 {@link McpEndpointProvider}）
	 * @param specs 工具规格（普通源为带 {@code {source}_} 前缀的全量注册；组合源为解析出的底层工具规格）
	 * @param base 组合源溯源（非组合源为 null，组合源为 includes/excludes 溯源）
	 */
	public record McpSource(String name, SourceType type, Protocol protocol, Scope scope,
			McpEndpointProvider provider, List<McpServerFeatures.AsyncToolSpecification> specs,
			CompositeBase base) {
	}

	private final List<McpSource> sources;

	/** 带前缀工具名 → 工具规格（全量注册）。 */
	private final Map<String, McpServerFeatures.AsyncToolSpecification> specsByName;

	/** 源名 → 源（普通源 + 组合源，用于源名展开）。 */
	private final Map<String, McpSource> sourcesByName;

	/** 组合源定义（{@code mcp.specs} 配置，空 = 不发布组合源）。 */
	private final Map<String, CompositeSpec> specDefs;

	/** 仅普通源（非组合）的源名 → 源（组合源静态解析时用于展开普通源 / 名字冲突检测）。 */
	private final Map<String, McpSource> baseSourcesByName;

	/** 工具名 → 所属普通源的部署范围（组合源 scope 派生用）。 */
	private final Map<String, Scope> toolScopes;

	/** 无组合源配置：等价于传入空 specDefs（行为与未配置完全一致）。 */
	public McpSourceRegistry(List<McpEndpointProvider> providers) {
		this(providers, Map.of());
	}

	/**
	 * 组装源注册表：先由 provider 演化普通源，再按 {@code mcp.specs} 静态解析并发布组合源（#33）。
	 *
	 * @param providers 普通源 provider（native / proxy / container）
	 * @param specDefs 组合源定义（组合源名 → includes/excludes），空 map 不发布任何组合源
	 */
	public McpSourceRegistry(List<McpEndpointProvider> providers, Map<String, CompositeSpec> specDefs) {
		List<McpSource> baseSources = providers.stream()
			// #35 起接纳 ProxyMcp（proxy 工具清单启动时发现）；#37 再迁入 ContainerMcp（容器源）
			.filter(provider -> provider instanceof NativeMcp
				|| provider instanceof ProxyMcpProvider || provider instanceof ContainerMcp)
			.filter(McpEndpointProvider::isEnabled)
			.map(this::toSource)
			.filter(Objects::nonNull)
			.toList();
		this.specDefs = specDefs == null ? Map.of() : Map.copyOf(specDefs);
		this.baseSourcesByName = baseSources.stream()
			.collect(Collectors.toMap(McpSource::name, Function.identity(), (a, b) -> a));
		this.specsByName = baseSources.stream()
			.flatMap(source -> source.specs().stream())
			.collect(Collectors.toMap(spec -> spec.tool().name(), Function.identity(), (a, b) -> {
				log.warn("工具名 {} 由多个源提供，保留先注册者", a.tool().name());
				return a;
			}));
		this.toolScopes = baseSources.stream()
			.flatMap(source -> source.specs().stream().map(spec -> Map.entry(spec.tool().name(), source.scope())))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
		List<McpSource> compositeSources = resolveComposites();
		this.sources = Stream.concat(baseSources.stream(), compositeSources.stream()).toList();
		this.sourcesByName = buildSourcesByName();
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
	 * 解析单项：先精确匹配工具名，再按源名展开——普通源为 {@code {source}_} 前缀匹配其全部工具，组合源
	 * 展开其启动时解析出的工具名；未知项静默忽略 + 日志 warn。
	 */
	private void applyItem(String item, Set<String> target, boolean add) {
		if (specsByName.containsKey(item)) {
			applyTool(item, target, add);
			return;
		}
		McpSource source = sourcesByName.get(item);
		if (source != null) {
			if (source.type() == SourceType.COMPOSITE) {
				// 组合源：直接展开解析出的工具（普通源工具名，同一 call handler）
				for (McpServerFeatures.AsyncToolSpecification spec : source.specs()) {
					applyTool(spec.tool().name(), target, add);
				}
			}
			else {
				for (String name : expandSource(source.name())) {
					applyTool(name, target, add);
				}
			}
			return;
		}
		log.warn("工具视图过滤：未知的 includes/excludes 项被忽略: {}", item);
	}

	private static void applyTool(String toolName, Set<String> target, boolean add) {
		if (add) {
			target.add(toolName);
		}
		else {
			target.remove(toolName);
		}
	}

	/** 普通源名 → 其全部工具名（{@code {source}_} 前缀展开）。 */
	private Set<String> expandSource(String sourceName) {
		String prefix = McpToolUtils.format(sourceName) + "_";
		return specsByName.keySet().stream()
			.filter(name -> name.startsWith(prefix))
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	// ---- 组合源静态解析（issue #33） ----

	/**
	 * 按 {@code mcp.specs} 配置解析并发布组合源（{@code type=composite}，带 {@code base} 溯源）。
	 * 解析规则（ADR-0011）：先精确匹配工具名，再按普通源名 {@code {source}_} 前缀展开，嵌套组合源递归
	 * 解析；循环定义抛 {@link IllegalStateException} fail-fast；未知项静默忽略 + warn。组合源不新增工具
	 * 名——其工具规格复用底层普通源的同名规格。
	 */
	private List<McpSource> resolveComposites() {
		if (specDefs.isEmpty()) {
			return List.of();
		}
		Map<String, Set<String>> memo = new LinkedHashMap<>();
		List<McpSource> composites = new ArrayList<>();
		for (Map.Entry<String, CompositeSpec> entry : specDefs.entrySet()) {
			String name = entry.getKey();
			CompositeSpec spec = entry.getValue();
			if (baseSourcesByName.containsKey(name)) {
				throw new IllegalStateException("组合源名与已注册源冲突: " + name + "（mcp.specs 与 provider 名不能重复）");
			}
			Set<String> toolNames = resolveComposite(name, new LinkedHashSet<>(), memo);
			List<McpServerFeatures.AsyncToolSpecification> specs = toolNames.stream()
				.map(specsByName::get)
				.filter(Objects::nonNull)
				.toList();
			composites.add(new McpSource(name, SourceType.COMPOSITE, null, compositeScope(specs), null, specs,
				new CompositeBase(spec.includes(), spec.excludes())));
		}
		log.info("组合源已发布 {} 个: {}", composites.size(), composites.stream().map(McpSource::name).toList());
		return composites;
	}

	/**
	 * 递归解析一个组合源为具体工具名集合（memo 化，保证每个组合源只解析一次）；命中 {@code visiting}
	 * 中的名字即定义循环，抛 {@link IllegalStateException}。
	 */
	private Set<String> resolveComposite(String name, Set<String> visiting, Map<String, Set<String>> memo) {
		Set<String> cached = memo.get(name);
		if (cached != null) {
			return cached;
		}
		if (!visiting.add(name)) {
			throw new IllegalStateException("组合源循环引用: " + String.join(" → ", visiting) + " → " + name);
		}
		CompositeSpec spec = specDefs.get(name);
		Set<String> tools = new LinkedHashSet<>();
		for (String include : spec.includes()) {
			tools.addAll(resolveItem(include, visiting, memo));
		}
		for (String exclude : spec.excludes()) {
			tools.removeAll(resolveItem(exclude, visiting, memo));
		}
		visiting.remove(name);
		memo.put(name, Set.copyOf(tools));
		return tools;
	}

	/**
	 * 解析一个平坦项为具体工具名集合：精确工具名 → 自身；普通源名 → {@code {source}_} 前缀全量；
	 * 组合源名 → 递归解析；未知项 → 空集 + warn。
	 */
	private Set<String> resolveItem(String item, Set<String> visiting, Map<String, Set<String>> memo) {
		if (specsByName.containsKey(item)) {
			return Set.of(item);
		}
		McpSource baseSource = baseSourcesByName.get(item);
		if (baseSource != null) {
			return expandSource(item);
		}
		if (specDefs.containsKey(item)) {
			return resolveComposite(item, visiting, memo);
		}
		log.warn("组合源解析：未知的 includes/excludes 项被忽略: {}", item);
		return Set.of();
	}

	/**
	 * 组合源 scope 派生：全部工具所属普通源均为 {@link Scope#HOST} 时为 host，否则 network
	 * （聚合了网络可达工具的组合源是 network；空组合源按 network 处理）。
	 */
	private Scope compositeScope(List<McpServerFeatures.AsyncToolSpecification> specs) {
		boolean allHost = !specs.isEmpty() && specs.stream()
			.map(spec -> toolScopes.getOrDefault(spec.tool().name(), Scope.NETWORK))
			.allMatch(scope -> scope == Scope.HOST);
		return allHost ? Scope.HOST : Scope.NETWORK;
	}

	/** 源名 → 源：普通源（保序）+ 组合源（追加）。 */
	private Map<String, McpSource> buildSourcesByName() {
		Map<String, McpSource> byName = new LinkedHashMap<>(baseSourcesByName);
		for (McpSource source : sources) {
			if (source.type() == SourceType.COMPOSITE) {
				byName.put(source.name(), source);
			}
		}
		return Collections.unmodifiableMap(byName);
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
		// scope 取 provider 部署范围；base 仅组合源填充（见 resolveComposites），普通源恒 null
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
