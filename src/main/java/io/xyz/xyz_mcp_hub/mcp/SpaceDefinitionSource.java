package io.xyz.xyz_mcp_hub.mcp;

import java.util.List;

/**
 * 空间定义源 —— 产出 {@link SpaceDefinition} 列表的 SPI（ADR-0008）。
 *
 * <p>本任务实现 YAML 来源（读 {@code mcp.spaces}）；未来 DB 来源与 UI（custom 维度）
 * 复用同一接口与 {@link SpaceDefinition} VO，接口即复用点。</p>
 */
@FunctionalInterface
public interface SpaceDefinitionSource {

	/**
	 * 加载全部空间定义。
	 *
	 * @return 空间定义列表；无定义时返回空列表
	 */
	List<SpaceDefinition> load();

}
