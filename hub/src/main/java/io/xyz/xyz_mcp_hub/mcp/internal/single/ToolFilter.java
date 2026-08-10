package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * URL 参数工具视图过滤器（ADR-0011）：从 {@code includes} / {@code excludes} 查询参数解析出的
 * 平坦项列表。
 *
 * <p>语法：{@code /xyz-hub/mcp?includes=[bocha,bocha_web_search]&excludes=[]}。项为下划线平坦名——
 * 源名（{@code bocha} → 该源全部工具）或工具名（{@code bocha_web_search} → 精确一个工具）。
 * 空参数 = 全量（向后兼容）。实际解析（精确匹配 / 源名展开 / 未知项忽略+warn）在
 * {@link McpSourceRegistry#visibleToolNames(ToolFilter)} 完成，本类只负责把 URL 参数解析成原始项列表。</p>
 *
 * @param includes 需选中的项（并集，空 = 全量）
 * @param excludes 需减去的项（空 = 不减）
 */
public record ToolFilter(List<String> includes, List<String> excludes) {

	/** 无过滤：全量工具视图。 */
	public static final ToolFilter EMPTY = new ToolFilter(List.of(), List.of());

	/**
	 * 从查询参数解析过滤器。参数缺省或为 {@code []} 时对应列表为空。
	 *
	 * @param includesParam 查询参数 includes（形如 {@code [a,b]}，可缺省）
	 * @param excludesParam 查询参数 excludes（形如 {@code [a,b]}，可缺省）
	 */
	public static ToolFilter parse(Optional<String> includesParam, Optional<String> excludesParam) {
		return new ToolFilter(parseList(includesParam), parseList(excludesParam));
	}

	/**
	 * 两个列表均为空时视为「无过滤」（全量视图）。
	 */
	public boolean isEmpty() {
		return includes.isEmpty() && excludes.isEmpty();
	}

	private static List<String> parseList(Optional<String> param) {
		if (param.isEmpty()) {
			return List.of();
		}
		String raw = param.get().trim();
		if (raw.startsWith("[") && raw.endsWith("]")) {
			raw = raw.substring(1, raw.length() - 1);
		}
		if (raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(","))
			.map(String::trim)
			.filter(item -> !item.isEmpty())
			.toList();
	}

}
