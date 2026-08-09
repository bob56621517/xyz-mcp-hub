package io.xyz.xyz_mcp_hub.mcp;

import java.util.List;

/**
 * 空间定义 —— Space 的纯数据 VO。
 *
 * <p>按可持久化、可被 UI 编辑的形状设计（ADR-0008）：{@code name} 唯一标识，
 * {@code path} 为暴露 URL（缺省 {@code /mcp/config/{name}}），{@code sources}
 * 列出引用来源。未来 UI（custom 维度）与 DB 来源复用同一 VO。</p>
 *
 * @param name  空间名，同 Hub 内唯一（决定默认路径与配置 key）
 * @param path  暴露路径；为 {@code null}/空白时用默认 {@code /mcp/config/{name}}
 * @param sources 组合引用列表，可为空（无来源则不注册）
 */
public record SpaceDefinition(String name, String path, List<SpaceSource> sources) {

	public SpaceDefinition {
		sources = sources == null ? List.of() : List.copyOf(sources);
	}

	/**
	 * 生效路径：显式 path 优先，否则默认 {@code /mcp/config/{name}}。
	 */
	public String effectivePath() {
		return path == null || path.isBlank() ? "/mcp/config/" + name : path;
	}

}
