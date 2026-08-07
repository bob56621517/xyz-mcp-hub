package io.xyz.xyz_mcp_hub.mcp.internal.space;

import io.xyz.xyz_mcp_hub.mcp.SpaceDefinition;
import io.xyz.xyz_mcp_hub.mcp.SpaceDefinitionSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAML 空间定义源单元测试：解析 {@code mcp.spaces} 的整拉入 / include / exclude /
 * 默认 path / 空配置。不加载 Spring 上下文，用真实 YAML 载入 Environment 验证绑定。
 */
class YamlSpaceDefinitionSourceTest {

	private SpaceDefinitionSource sourceFor(String yaml) throws IOException {
		var yamlSource = new YamlPropertySourceLoader()
			.load("test", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))
			.get(0);
		var env = new StandardEnvironment();
		env.getPropertySources().addFirst(yamlSource);
		return new YamlSpaceDefinitionSource(env);
	}

	@Test
	void parsesWholeIncludeExcludeAndDefaultPath() throws IOException {
		var source = sourceFor("""
			mcp:
			  spaces:
			    devops:
			      sources:
			        - source: github-readonly
			        - source: utils
			          include: [currentDateTime]
			        - source: bocha
			          exclude: [ai_search]
			    quick:
			      path: /mcp/config/my-quick
			      sources:
			        - source: utils
			""");

		var defs = source.load();
		assertThat(defs).hasSize(2);

		var devops = defs.get(0);
		assertThat(devops.name()).isEqualTo("devops");
		assertThat(devops.effectivePath()).isEqualTo("/mcp/config/devops");
		assertThat(devops.sources()).hasSize(3);
		assertThat(devops.sources().get(0)).satisfies(s -> {
			assertThat(s.source()).isEqualTo("github-readonly");
			assertThat(s.includesAll()).isTrue();
		});
		assertThat(devops.sources().get(1).include()).containsExactly("currentDateTime");
		assertThat(devops.sources().get(2).exclude()).containsExactly("ai_search");

		assertThat(defs.get(1).name()).isEqualTo("quick");
		assertThat(defs.get(1).effectivePath()).isEqualTo("/mcp/config/my-quick");
	}

	@Test
	void includeAndExcludeCombined() throws IOException {
		var source = sourceFor("""
			mcp:
			  spaces:
			    combo:
			      sources:
			        - source: playwright
			          include: [browser_navigate, browser_click]
			          exclude: [browser_click]
			""");

		var defs = source.load();
		assertThat(defs).hasSize(1);
		var combo = defs.get(0);
		assertThat(combo.sources()).hasSize(1);
		assertThat(combo.sources().get(0).include()).containsExactly("browser_navigate", "browser_click");
		assertThat(combo.sources().get(0).exclude()).containsExactly("browser_click");
	}

	@Test
	void emptySpacesSectionReturnsEmptyList() throws IOException {
		var source = sourceFor("mcp:\n  spaces: {}\n");
		assertThat(source.load()).isEmpty();
	}

	@Test
	void missingSpacesKeyReturnsEmptyList() throws IOException {
		var source = sourceFor("bocha:\n  api-key: x\n");
		assertThat(source.load()).isEmpty();
	}

	@Test
	void spaceWithoutSourcesStillListed() throws IOException {
		var source = sourceFor("""
			mcp:
			  spaces:
			    bare:
			      path: /mcp/config/bare
			""");
		var defs = source.load();
		assertThat(defs).hasSize(1);
		assertThat(defs.get(0).sources()).isEmpty();
		assertThat(defs.get(0).effectivePath()).isEqualTo("/mcp/config/bare");
	}

}
