package io.xyz.xyz_mcp_hub.mcp.internal.space;

import io.xyz.xyz_mcp_hub.mcp.SpaceDefinition;
import io.xyz.xyz_mcp_hub.mcp.SpaceSource;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Space 工具物化单元测试：整拉入 / include / exclude（排除优先）/ 源不可用跳过 /
 * 冲突后覆盖 / include 缺失工具 fail-fast。不加载 Spring 上下文，用函数式源解析器。
 */
class SpaceToolMaterializerTest {

	private static final List<ToolCallback> UTILS = List.of(
			tool("alpha", "来自 utils 的 alpha"), tool("beta", "B"), tool("gamma", "G"));

	private static final List<ToolCallback> OTHER = List.of(tool("alpha", "来自 other 的 alpha"), tool("delta", "D"));

	private static ToolCallback tool(String name, String description) {
		ToolDefinition definition = ToolDefinition.builder()
			.name(name)
			.description(description)
			.inputSchema("{\"type\":\"object\"}")
			.build();
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return name;
			}
		};
	}

	@Test
	void wholePullIncludesAllTools() {
		var def = new SpaceDefinition("s", null, List.of(new SpaceSource("utils", List.of(), List.of())));
		var result = materialize(def);
		assertThat(names(result)).containsExactly("alpha", "beta", "gamma");
	}

	@Test
	void includeSelectsOnlyListedTools() {
		var def = new SpaceDefinition("s", null, List.of(new SpaceSource("utils", List.of("beta"), List.of())));
		var result = materialize(def);
		assertThat(names(result)).containsExactly("beta");
	}

	@Test
	void excludeRemovesListedTools() {
		var def = new SpaceDefinition("s", null, List.of(new SpaceSource("utils", List.of(), List.of("beta"))));
		var result = materialize(def);
		assertThat(names(result)).containsExactly("alpha", "gamma");
	}

	@Test
	void excludeWinsOverInclude() {
		var def = new SpaceDefinition("s", null,
				List.of(new SpaceSource("utils", List.of("alpha", "beta", "gamma"), List.of("beta"))));
		var result = materialize(def);
		assertThat(names(result)).containsExactly("alpha", "gamma");
	}

	@Test
	void unavailableSourceIsSkipped() {
		var def = new SpaceDefinition("s", null, List.of(new SpaceSource("unknown", List.of(), List.of())));
		var result = materialize(def);
		assertThat(result).isEmpty();
	}

	@Test
	void conflictingToolNameLaterSourceOverrides() {
		var def = new SpaceDefinition("s", null, List.of(
				new SpaceSource("utils", List.of(), List.of()),
				new SpaceSource("other", List.of(), List.of())));
		var result = materialize(def);
		// 工具名去重；other 的 alpha 后注册，覆盖 utils 的 alpha（description 为后者的）
		assertThat(names(result)).containsExactly("alpha", "beta", "gamma", "delta");
		assertThat(result.get(0).getToolDefinition().description()).isEqualTo("来自 other 的 alpha");
	}

	@Test
	void includeOfMissingToolFailsFast() {
		var def = new SpaceDefinition("s", null,
				List.of(new SpaceSource("utils", List.of("nonexistent"), List.of())));
		assertThatThrownBy(() -> materialize(def))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("nonexistent");
	}

	@Test
	void excludeOfMissingToolFailsFast() {
		var def = new SpaceDefinition("s", null,
				List.of(new SpaceSource("utils", List.of(), List.of("nonexistent"))));
		assertThatThrownBy(() -> materialize(def))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("nonexistent");
	}

	private static List<ToolCallback> materialize(SpaceDefinition def) {
		return SpaceToolMaterializer.materialize(def, name -> switch (name) {
			case "utils" -> UTILS;
			case "other" -> OTHER;
			default -> null;
		});
	}

	private static List<String> names(List<ToolCallback> tools) {
		return tools.stream().map(t -> t.getToolDefinition().name()).toList();
	}

}
