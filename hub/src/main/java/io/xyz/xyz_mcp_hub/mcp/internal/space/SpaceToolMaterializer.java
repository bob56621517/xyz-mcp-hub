package io.xyz.xyz_mcp_hub.mcp.internal.space;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.xyz.xyz_mcp_hub.mcp.SpaceDefinition;
import io.xyz.xyz_mcp_hub.mcp.SpaceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Space 工具物化器：把组合引用的源端点工具聚合为 {@link ToolCallback} 列表（ADR-0008 语义）。
 *
 * <ul>
 * <li>两列表空 = 整端点拉入；先 include 后 exclude（排除优先）</li>
 * <li>源工具列表为 {@code null}（源不可用）→ 跳过该条引用</li>
 * <li>include/exclude 引用不存在的工具 → fail-fast（启动报错）</li>
 * <li>工具名冲突 → 后注册者覆盖 + 告警</li>
 * </ul>
 */
final class SpaceToolMaterializer {

	private static final Logger log = LoggerFactory.getLogger(SpaceToolMaterializer.class);

	private SpaceToolMaterializer() {
	}

	/**
	 * 物化一个 Space 定义。
	 *
	 * @param sourceTools 按源端点名解析其完整工具列表；源不可用时返回 {@code null}
	 * @return 去重后的工具列表，保持来源顺序
	 */
	static List<ToolCallback> materialize(SpaceDefinition definition,
			Function<String, List<ToolCallback>> sourceTools) {
		Map<String, ToolCallback> merged = new LinkedHashMap<>();
		for (SpaceSource source : definition.sources()) {
			List<ToolCallback> all = sourceTools.apply(source.source());
			if (all == null) {
				continue;
			}
			Set<String> available = all.stream()
				.map(tool -> tool.getToolDefinition().name())
				.collect(Collectors.toSet());
			// include 与 exclude 引用的工具名都必须存在于源端点，否则启动 fail-fast
			List<String> missing = Stream.concat(source.include().stream(), source.exclude().stream())
				.distinct()
				.filter(name -> !available.contains(name))
				.toList();
			if (!missing.isEmpty()) {
				throw new IllegalStateException("Space " + definition.name() + " 引用源 " + source.source()
						+ " 的工具不存在：" + missing);
			}
			for (ToolCallback tool : all) {
				String name = tool.getToolDefinition().name();
				if (!source.include().isEmpty() && !source.include().contains(name)) {
					continue;
				}
				if (source.exclude().contains(name)) {
					continue;
				}
				ToolCallback previous = merged.put(name, tool);
				if (previous != null) {
					log.warn("Space {} 的工具 {} 由多个源提供，后注册者覆盖", definition.name(), name);
				}
			}
		}
		return List.copyOf(merged.values());
	}

}
