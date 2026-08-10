package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;

/**
 * 组合源溯源（目录 API，ADR-0011 / issue #34）：组合源由 {@code mcp.specs} YAML
 * （{@code specName: {includes, excludes}}）发布为目录里的一个派生源，{@code base} 记录其
 * 过滤溯源——与 URL 参数 / YAML 同一套下划线平坦名语法（源名或工具名）。
 *
 * <p>#34 先定稿 schema，{@code base} 对非组合源恒为 {@code null}；#33 起由
 * {@link McpSourceRegistry} 在启动时解析 {@code mcp.specs} 并填充到组合源的 {@code base}。</p>
 *
 * @param includes 组合源 includes（源名或工具名平坦项）
 * @param excludes 组合源 excludes（源名或工具名平坦项）
 */
public record CompositeBase(List<String> includes, List<String> excludes) {
}
