package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
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
 * <p>注册/启用分离（#50，ADR-0005 二次修订）：注册表**不再用 {@code isEnabled()} 过滤源**——
 * 所有 provider 演化成 source（代码/配置固定），{@code enabled} 由 {@code isEnabled()} 决定。
 * 未启用源（缺 key/token/docker）目录列出、{@code enabled=false}、工具为空、工具不进全量表；被 URL
 * 引用得空集 + warn。proxy 上游不可达（连接/握手/listTools 失败）时源仍注册、{@code enabled} 保持
 * 配置值、工具为空 + 日志（ADR-0005），应用照常启动、不拖垮启动（#35）。</p>
 *
 * <p>每个源携带目录元数据（{@link McpSource}：type / protocol / scope / enabled，issue #34 / #50），
 * 供目录 API（{@link CatalogEndpoint}）读取；proxy / container 源迁入后目录自动增长。</p>
 *
 * <p>解析规则（ADR-0011，#51 严格语义）：{@code includes} 缺失 ≡ 全量，显式 {@code includes=[]} =
 * 空集，{@code excludes} 缺失 ≡ 不减；项为工具名（下划线平坦名），支持 {@code *} 通配（裸 {@code *} =
 * 全量），源名匹配已退役；通配/未知项匹配不到任何工具 → 静默忽略 + 日志 warn，不使连接失败。</p>
 */
public class McpSourceRegistry {

	private static final Logger log = LoggerFactory.getLogger(McpSourceRegistry.class);

	/**
	 * 单个源：源名 + 目录元数据（type/protocol/scope/enabled，ADR-0011）+ 底层 provider + 带前缀的工具规格。
	 *
	 * <p>元数据模型（#34）为 #35/#36/#37 共用、一次定稿：{@code type} 由 provider 声明
	 * （{@link McpEndpointProvider#getSourceType()}）；{@code protocol} 为 container 专有（mcp|rest，
	 * 非容器源为 null）；{@code scope} 取 provider 部署范围；{@code enabled} 为注册/启用分离（#50）——
	 * 源仍已注册（代码/配置固定），是否启用由 {@code isEnabled()} 决定。未启用源 {@code specs} 为空、
	 * 工具不进全量表。组合源已整体移除（#49），不再有 {@code base} 溯源字段。</p>
	 *
	 * @param name 源名（目录元数据 / 工具名 {@code {source}_} 前缀；源名不再被 URL 引用，#51）
	 * @param type 源类型（native/proxy/container，#50 收敛）
	 * @param protocol 容器接入协议（container 专有，其余为 null）
	 * @param scope 部署范围（host/network）
	 * @param enabled 是否启用（未启用源目录列出、tools 空）
	 * @param provider 底层端点提供者
	 * @param specs 工具规格（带 {@code {source}_} 前缀的注册；未启用源为空）
	 */
	public record McpSource(String name, SourceType type, Protocol protocol, Scope scope, boolean enabled,
			McpEndpointProvider provider, List<McpServerFeatures.AsyncToolSpecification> specs) {
	}

	private final List<McpSource> sources;

	/** 带前缀工具名 → 工具规格（全量注册）。 */
	private final Map<String, McpServerFeatures.AsyncToolSpecification> specsByName;

	public McpSourceRegistry(List<McpEndpointProvider> providers) {
		// #50 注册/启用分离：不再用 isEnabled() 过滤——所有 provider 演化成 source，enabled 由
		// isEnabled() 决定（未启用源 specs 为空、工具不进全量表；proxy 上游不可达工具空 + 日志）。
		// #53 工具类即源：工具类本身实现 McpEndpointProvider（BochaTools/PlaywrightTools/JinaTools），
		// 与 ProxyMcpProvider / ContainerMcp 子类一并作为输入，不再按类型过滤——任何 McpEndpointProvider
		// bean 都是源。
		List<McpSource> sources = providers.stream()
			.map(this::toSource)
			.filter(Objects::nonNull)
			.toList();
		this.sources = sources;
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
	 * <p>严格语义（#51）：{@code includes} 缺失 ≡ {@code [*]} 全量；显式 {@code includes=[]} =
	 * 空集（不引入任何工具）；{@code excludes} 缺失或空 = 不减。项为工具名（下划线平坦名），支持
	 * {@code *} 通配（裸 {@code *} = 全量），源名匹配已退役；通配/未知项匹配不到任何工具 → 静默忽略
	 * + warn，不使连接失败。</p>
	 */
	public Set<String> visibleToolNames(ToolFilter filter) {
		Set<String> all = allToolNames();
		if (filter == null) {
			return all;
		}
		Set<String> selected = new LinkedHashSet<>();
		if (filter.includesAbsent()) {
			selected.addAll(all);
		}
		else {
			for (String item : filter.includes().get()) {
				applyItem(item, selected, true);
			}
		}
		filter.excludes().ifPresent(excludes -> {
			for (String item : excludes) {
				applyItem(item, selected, false);
			}
		});
		return selected;
	}

	/** 工具是否在过滤器给定的视图中可见。 */
	public boolean isVisible(String toolName, ToolFilter filter) {
		return visibleToolNames(filter).contains(toolName);
	}

	/**
	 * 解析单项（工具名，#51 源名匹配退役）：支持 {@code *} 通配（裸 {@code *} = 全量；
	 * {@code bocha*} 前缀 / {@code *search} 后缀 / {@code bo*search} 中间），不支持 {@code ?}（按字面
	 * 处理）；无 {@code *} 的项按精确工具名匹配；匹配不到任何工具 → 未知项 warn，不使连接失败。
	 */
	private void applyItem(String item, Set<String> target, boolean add) {
		boolean matched = false;
		if (item.indexOf('*') >= 0) {
			Pattern pattern = wildcardPattern(item);
			for (String name : specsByName.keySet()) {
				if (pattern.matcher(name).matches()) {
					applyTool(name, target, add);
					matched = true;
				}
			}
		}
		else if (specsByName.containsKey(item)) {
			applyTool(item, target, add);
			matched = true;
		}
		if (!matched) {
			log.warn("工具视图过滤：未知的 includes/excludes 项被忽略: {}", item);
		}
	}

	/** {@code *} 通配模式 → 正则：{@code *} → {@code .*}，其余字符按字面（{@code ?} 无通配语义）。 */
	private static Pattern wildcardPattern(String item) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < item.length(); i++) {
			char c = item.charAt(i);
			if (c == '*') {
				regex.append(".*");
			}
			else {
				regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString());
	}

	private static void applyTool(String toolName, Set<String> target, boolean add) {
		if (add) {
			target.add(toolName);
		}
		else {
			target.remove(toolName);
		}
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

	/**
	 * 把一个 provider 化为 source：工具名加 {@code {source}_} 前缀；目录元数据取自 provider 声明；
	 * {@code enabled} 由 {@code isEnabled()} 决定（#50）。未启用源 {@code specs} 为空、工具不进全量表。
	 */
	private McpSource toSource(McpEndpointProvider provider) {
		String sourceName = provider.getName();
		boolean enabled = provider.isEnabled();
		if (provider instanceof ProxyMcpProvider proxy) {
			// #35 ProxyMcp 迁移：proxy 源工具来自上游 listTools（启动时发现），适配为 AsyncToolSpecification
			return toProxySource(sourceName, proxy, enabled);
		}
		List<McpServerFeatures.AsyncToolSpecification> specs = enabled
			? provider.getTools().stream()
				.map(toolCallback -> toPrefixedSpec(sourceName, toolCallback))
				.toList()
			: List.of();
		// #34 目录元数据：type 由 provider 声明；protocol 仅容器源有（mcp|rest，#53 起统一取 provider
		// 声明——ContainerMcp 子类从清单读取、容器型工具类即源如 JinaTools 返回常量）；scope 取 provider
		// 部署范围。未启用容器源（docker 缺失/清单缺规格）不取 protocol（requireSpec 会抛）
		Protocol protocol = enabled ? provider.getProtocol() : null;
		return new McpSource(sourceName, provider.getSourceType(), protocol, provider.getScope(), enabled, provider, specs);
	}

	/**
	 * proxy 源：启动时向上游 {@code listTools} 发现工具并缓存，工具名加 {@code {source}_} 前缀；
	 * 目录元数据与原生源一致取 provider 声明（type=PROXY，见 #34/#35）。未启用源（如缺 token）不入
	 * 上游发现，{@code enabled=false}、工具为空。上游不可达（连接/握手/listTools 失败）时源仍注册、
	 * {@code enabled} 保持配置值、工具为空 + 日志（ADR-0005 注册/启用分离，#50）。
	 */
	private McpSource toProxySource(String sourceName, ProxyMcpProvider proxy, boolean enabled) {
		if (!enabled) {
			log.info("proxy 源 {} 未启用（isEnabled=false），目录列出 enabled=false、工具为空", sourceName);
			return new McpSource(sourceName, proxy.getSourceType(), null, proxy.getScope(), false, proxy, List.of());
		}
		try {
			List<McpServerFeatures.AsyncToolSpecification> specs = proxy.discoverTools().stream()
				.map(spec -> renamePrefixed(sourceName, spec))
				.toList();
			return new McpSource(sourceName, proxy.getSourceType(), null, proxy.getScope(), true, proxy, specs);
		}
		catch (RuntimeException e) {
			// #50 注册/启用分离：上游不可达不再把源整体剔除出目录——源仍注册、enabled 保持配置值、
			// 工具为空 + 日志，应用照常启动、不拖垮启动（#35）
			log.error("proxy 源 {} 启动发现失败，工具为空（源已注册但上游不可达）: {}", sourceName, e.getMessage());
			return new McpSource(sourceName, proxy.getSourceType(), null, proxy.getScope(), true, proxy, List.of());
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
