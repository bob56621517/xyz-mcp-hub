package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * URL 参数工具视图过滤器（ADR-0011，#51 严格语义）：从 {@code includes} / {@code excludes} 查询
 * 参数解析出的平坦工具名项列表。
 *
 * <p>语法：{@code /xyz-hub/mcp?includes=[bocha*,_*search]&excludes=[]}。项为下划线平坦工具名，
 * 支持 {@code *} 通配（裸 {@code *} = 全量；前缀/后缀/中间），不支持 {@code ?}（按字面处理）。源名
 * 匹配已退役（#51）——要某源全部工具写 {@code bocha*}。实际匹配（通配 / 精确 / 未知项忽略+warn）在
 * {@link McpSourceRegistry#visibleToolNames(ToolFilter)} 完成，本类只负责把 URL 参数解析成原始项
 * 列表并保留「参数缺失 vs 显式空集」的区分。</p>
 *
 * <p>严格语义（#51，无语法糖）：{@code includes} 参数缺失 ≡ {@code [*]}（全量）；显式
 * {@code includes=[]} = 空集（不引入任何工具）；{@code excludes} 参数缺失 ≡ {@code []}（不减）。</p>
 *
 * @param includes 需选中的工具名项（参数缺失 = 全量；显式空列表 = 空集）
 * @param excludes 需减去的工具名项（参数缺失或空 = 不减）
 */
public record ToolFilter(Optional<List<String>> includes, Optional<List<String>> excludes) {

	/** 全量视图：includes/excludes 均未给出（无参数 = 全量，向后兼容）。 */
	public static final ToolFilter ALL = new ToolFilter(Optional.empty(), Optional.empty());

	/**
	 * 从查询参数解析过滤器。区分「参数缺失」与「显式空集」：参数缺省 → {@link Optional#empty()}；
	 * 参数给出（含 {@code []}）→ {@link Optional#of(List)}。参数非 {@code []} 时解析方括号内逗号
	 * 分隔的项（去掉首尾空白与空项）。
	 *
	 * @param includesParam 查询参数 includes（形如 {@code [a,b]}，可缺省）
	 * @param excludesParam 查询参数 excludes（形如 {@code [a,b]}，可缺省）
	 */
	public static ToolFilter parse(Optional<String> includesParam, Optional<String> excludesParam) {
		return new ToolFilter(parseList(includesParam), parseList(excludesParam));
	}

	/**
	 * URL 是否带任何显式过滤参数（includes 或 excludes 任一出现，含显式 {@code []}）；用于 SSE 会话
	 * 暂存等「请求是否传参」判断。注意显式 {@code includes=[]} 也算带参数（空集 ≠ 缺失）。
	 */
	public boolean hasExplicitFilter() {
		return includes.isPresent() || excludes.isPresent();
	}

	/** {@code includes} 参数是否缺失（缺失 ≡ 全量）。 */
	public boolean includesAbsent() {
		return includes.isEmpty();
	}

	private static Optional<List<String>> parseList(Optional<String> param) {
		if (param.isEmpty()) {
			return Optional.empty();
		}
		String raw = param.get().trim();
		if (raw.startsWith("[") && raw.endsWith("]")) {
			raw = raw.substring(1, raw.length() - 1);
		}
		if (raw.isBlank()) {
			return Optional.of(List.of());
		}
		List<String> items = Arrays.stream(raw.split(","))
			.map(String::trim)
			.filter(item -> !item.isEmpty())
			.toList();
		return Optional.of(items);
	}

}
