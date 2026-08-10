package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;

/**
 * 源注册表（ADR-0011）：收集所有 {@link McpEndpointProvider}，把「provider 演化为 source」，为单
 * McpServer 提供统一的工具注册与 URL 参数工具视图解析。
 *
 * <p>本议题（#30）首批迁入原生源：注册所有 {@link NativeMcp}（utils / bocha / fetch / playwright），
 * 工具名统一加 {@code {source}_} 前缀保证跨源全局唯一（MCP 工具名规范不允许点，暴露名与语法名
 * 同一套体系，零映射）。proxy 与 space 仍只走旧多端点，不在注册表内（后续议题迁入）。</p>
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
			.filter(provider -> provider instanceof NativeMcp)
			.filter(McpEndpointProvider::isEnabled)
			.map(this::toSource)
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

	/** 把一个 provider 化为 source：工具名加 {@code {source}_} 前缀；目录元数据取自 provider 声明。 */
	private McpSource toSource(McpEndpointProvider provider) {
		String sourceName = provider.getName();
		List<McpServerFeatures.AsyncToolSpecification> specs = provider.getTools().stream()
			.map(toolCallback -> toPrefixedSpec(sourceName, toolCallback))
			.toList();
		// #34 目录元数据：type 由 provider 声明；protocol 仅容器源有（本期无容器源，恒 null）；
		// scope 取 provider 部署范围；base 为组合源溯源（#33 后填充，本期恒 null）
		return new McpSource(sourceName, provider.getSourceType(), null, provider.getScope(), provider, specs, null);
	}

	private McpServerFeatures.AsyncToolSpecification toPrefixedSpec(String sourceName, ToolCallback toolCallback) {
		McpServerFeatures.AsyncToolSpecification spec = McpToolUtils.toAsyncToolSpecification(toolCallback);
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
