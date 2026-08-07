package io.xyz.xyz_mcp_hub.mcp.internal.space;

import java.util.List;
import java.util.Map;

import io.xyz.xyz_mcp_hub.mcp.SpaceDefinition;
import io.xyz.xyz_mcp_hub.mcp.SpaceDefinitionSource;
import io.xyz.xyz_mcp_hub.mcp.SpaceSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * YAML 空间定义源：从 {@code mcp.spaces} 配置声明组合 Space（ADR-0008）。
 *
 * <p>配置 schema：{@code mcp.spaces.<name>.path?} + {@code mcp.spaces.<name>.sources[]}
 * （每项 {@code source} + 可选 {@code include}/{@code exclude}）。经 Spring Boot
 * {@link Binder} 绑定为领域 VO；缺省不设置 key 时返回空列表（缺配置不注册）。</p>
 */
@Component
public class YamlSpaceDefinitionSource implements SpaceDefinitionSource {

	private final Environment environment;

	public YamlSpaceDefinitionSource(Environment environment) {
		this.environment = environment;
	}

	@Override
	public List<SpaceDefinition> load() {
		// yaml 空 map（`spaces: {}`）会被扁平化为空字符串，Binder 无法转 Map，此处显式短路
		String spacesValue = environment.getProperty("mcp.spaces");
		if (spacesValue != null && spacesValue.isBlank()) {
			return List.of();
		}
		SpaceConfig config = Binder.get(environment)
			.bind("mcp", Bindable.of(SpaceConfig.class))
			.orElseGet(SpaceConfig::empty);
		return config.spaces().entrySet().stream().map(e -> toDefinition(e.getKey(), e.getValue())).toList();
	}

	private SpaceDefinition toDefinition(String name, SpaceYaml yaml) {
		List<SpaceSource> sources = yaml.sources() == null
			? List.of()
			: yaml.sources().stream()
				.map(s -> new SpaceSource(s.source(), s.include(), s.exclude()))
				.toList();
		return new SpaceDefinition(name, yaml.path(), sources);
	}

	/** {@code mcp} 节点的绑定形状；{@code spaces} 缺失/为空时规范化为空 map。 */
	record SpaceConfig(Map<String, SpaceYaml> spaces) {

		SpaceConfig {
			spaces = spaces == null ? Map.of() : spaces;
		}

		static SpaceConfig empty() {
			return new SpaceConfig(Map.of());
		}

	}

	/** {@code mcp.spaces.<name>} 的绑定形状。 */
	record SpaceYaml(String path, List<SpaceSourceYaml> sources) {
	}

	/** {@code sources[]} 单项的绑定形状。 */
	record SpaceSourceYaml(String source, List<String> include, List<String> exclude) {
	}

}
