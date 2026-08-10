package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 组合源配置（前缀 {@code mcp}，字段 {@code specs}，ADR-0011 / issue #33）：
 * {@code mcp.specs.specName: {includes, excludes}}。
 *
 * <p>前缀取 {@code mcp} 而非 {@code mcp.specs}：@ConfigurationProperties 绑定时字段名必须出现在
 * 配置路径中（{@code mcp.specs.readonly.includes} → 前缀 {@code mcp} + map 字段 {@code specs}
 * + map 键 {@code readonly} + 子属性 {@code includes}）。（旧 {@code mcp.spaces} 组合端点配置已随
 * 旧多端点移除，issue #39。）</p>
 *
 * <p>默认不声明任何组合源（空 map，行为与未配置完全一致）；声明后由 {@link McpSourceRegistry}
 * 启动时静态解析并发布为目录中的 {@code type: composite} 源，被 {@code includes} 引用时与普通源等效。
 * 由 {@code @ConfigurationPropertiesScan} 扫描注册（同 {@code DockerProperties} / {@code PlaywrightProperties}）。</p>
 */
@ConfigurationProperties(prefix = "mcp")
public class CompositeSourceProperties {

	/** 组合源名 → 定义（includes/excludes 项为下划线平坦名，源名 / 工具名 / 组合源名）。 */
	private Map<String, CompositeSpec> specs = new LinkedHashMap<>();

	public Map<String, CompositeSpec> getSpecs() {
		return specs;
	}

	public void setSpecs(Map<String, CompositeSpec> specs) {
		this.specs = specs;
	}

}
