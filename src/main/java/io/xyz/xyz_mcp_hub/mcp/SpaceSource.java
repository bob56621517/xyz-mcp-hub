package io.xyz.xyz_mcp_hub.mcp;

import java.util.List;

/**
 * 组合引用 —— Space 的一个来源。
 *
 * <p>{@code source} 引用已注册端点名；{@code include}/{@code exclude} 为精确工具名
 * 枚举（本任务不支持通配符，见 ADR-0008）。两列表空 = 整端点拉入；先 include 后
 * exclude（排除优先）。</p>
 *
 * @param source  源端点名（如 {@code utils} / {@code github-readonly}）
 * @param include 只保留的工具名；空 = 不限制
 * @param exclude 排除的工具名；空 = 不排除
 */
public record SpaceSource(String source, List<String> include, List<String> exclude) {

	public SpaceSource {
		include = include == null ? List.of() : List.copyOf(include);
		exclude = exclude == null ? List.of() : List.copyOf(exclude);
	}

	/**
	 * 是否整端点拉入：include 与 exclude 均为空。
	 */
	public boolean includesAll() {
		return include.isEmpty() && exclude.isEmpty();
	}

}
