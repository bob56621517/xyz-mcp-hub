package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 组合源定义（{@code mcp.specs} 配置项，ADR-0011 / issue #33）：{@code specName: {includes, excludes}}。
 *
 * <p>项与 URL 参数同一套下划线平坦名语法——源名（{@code bocha} → 该源全部工具）或工具名
 * （{@code bocha_web_search} → 精确一个工具）；组合源名亦为合法项（嵌套）。启动时由
 * {@link McpSourceRegistry} 静态解析（循环检测）并发布为目录里的 {@code type: composite} 新源。</p>
 *
 * <p>record 构造器绑定（value object），作为 {@link CompositeSourceProperties} 中
 * {@code Map<String, CompositeSpec>} 的 map 值类型；{@code includes} / {@code excludes} 任一缺省时
 * 默认为空列表（{@link DefaultValue}，避免构造器绑定把缺失字段置为 null）。</p>
 *
 * @param includes 需选中的项（并集，可含源名 / 工具名 / 组合源名）
 * @param excludes 需减去的项（与 includes 同语法）
 */
public record CompositeSpec(@DefaultValue List<String> includes, @DefaultValue List<String> excludes) {
}
