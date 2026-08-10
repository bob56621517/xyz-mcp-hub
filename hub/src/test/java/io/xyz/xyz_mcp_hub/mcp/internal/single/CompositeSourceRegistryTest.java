package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host.HostMcp;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组合源（{@code mcp.specs}）纯逻辑单测（issue #33）：specs 解析（工具精确 / 源名展开 / 嵌套 /
 * 循环检测 fail-fast）、发布成目录 {@code type=composite} 带 {@code base} 溯源、被 URL
 * includes/excludes 引用时与普通源等效、组合源 scope 派生。无外部依赖（不启动 Spring 上下文）。
 */
class CompositeSourceRegistryTest {

	/** 假原生源：带两个工具的 alpha 源（NETWORK）。 */
	private static class AlphaSource extends NativeMcp {

		private final List<ToolCallback> tools;

		AlphaSource(String toolA, String toolB) {
			super(Scope.NETWORK);
			this.tools = toolCallbacks(toolA, toolB);
		}

		@Override
		public String getName() {
			return "alpha";
		}

		@Override
		public List<ToolCallback> getTools() {
			return tools;
		}

	}

	/** 假原生源：单个工具的 beta 源（NETWORK）。 */
	private static class BetaSource extends NativeMcp {

		private final List<ToolCallback> tools;

		BetaSource(String tool) {
			super(Scope.NETWORK);
			this.tools = toolCallbacks(tool);
		}

		@Override
		public String getName() {
			return "beta";
		}

		@Override
		public List<ToolCallback> getTools() {
			return tools;
		}

	}

	/** 假主机源（HOST）：组合源 scope 派生用。 */
	private static class HostSource extends HostMcp {

		private final List<ToolCallback> tools;

		HostSource(String tool) {
			this.tools = toolCallbacks(tool);
		}

		@Override
		public String getName() {
			return "hosty";
		}

		@Override
		public List<ToolCallback> getTools() {
			return tools;
		}

	}

	/** @Tool 注解的假工具集合（方法名即工具名）。 */
	static final class FakeTools {

		@org.springframework.ai.tool.annotation.Tool(description = "fake a")
		public String toolA() {
			return "a";
		}

		@org.springframework.ai.tool.annotation.Tool(description = "fake b")
		public String toolB() {
			return "b";
		}

		@org.springframework.ai.tool.annotation.Tool(description = "fake c")
		public String toolC() {
			return "c";
		}

		@org.springframework.ai.tool.annotation.Tool(description = "fake d")
		public String toolD() {
			return "d";
		}

	}

	private static List<ToolCallback> toolCallbacks(String... names) {
		return List.of(MethodToolCallbackProvider.builder().toolObjects(new FakeTools()).build().getToolCallbacks())
			.stream()
			.filter(callback -> names.length == 0 || List.of(names).contains(callback.getToolDefinition().name()))
			.toList();
	}

	/** alpha(toolA/toolB) + beta(toolC) + hosty(toolD) + 指定组合源定义。 */
	private McpSourceRegistry registry(Map<String, CompositeSpec> specs) {
		return new McpSourceRegistry(
				List.of(new AlphaSource("toolA", "toolB"), new BetaSource("toolC"), new HostSource("toolD")), specs);
	}

	private static McpSourceRegistry.McpSource compositeOf(McpSourceRegistry registry) {
		return registry.sources().stream().filter(source -> source.type() == SourceType.COMPOSITE).findFirst().orElseThrow();
	}

	private static ToolFilter includes(String item) {
		return ToolFilter.parse(Optional.of("[" + item + "]"), Optional.empty());
	}

	// ---- 验收 1：组合源发布成目录里的新源（type=composite，带 base 溯源） ----

	@Test
	void compositeSourceIsPublishedWithCompositeTypeAndBaseTracing() {
		var registry = registry(Map.of("all-alpha", new CompositeSpec(List.of("alpha"), List.of())));
		McpSourceRegistry.McpSource composite = compositeOf(registry);
		assertThat(composite.name()).isEqualTo("all-alpha");
		assertThat(composite.type()).isEqualTo(SourceType.COMPOSITE);
		assertThat(composite.protocol()).isNull();
		assertThat(composite.provider()).isNull();
		assertThat(composite.base()).isEqualTo(new CompositeBase(List.of("alpha"), List.of()));
		// 工具复用底层普通源规格（同一工具名，同一 call handler）
		assertThat(composite.specs()).extracting(spec -> spec.tool().name())
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
		// 组合源不新增工具名进全量视图
		assertThat(registry.allToolNames())
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC", "hosty_toolD");
		// 普通源 base 恒为 null，不受组合源影响
		assertThat(registry.sources().stream().filter(s -> s.type() != SourceType.COMPOSITE).toList())
			.allSatisfy(source -> assertThat(source.base()).isNull());
	}

	// ---- 验收 2：includes=组合源 与普通源一致生效 ----

	@Test
	void includesCompositeNameExpandsToResolvedTools() {
		var registry = registry(Map.of("all-alpha", new CompositeSpec(List.of("alpha"), List.of())));
		assertThat(registry.visibleToolNames(includes("all-alpha")))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void compositeExcludesSubtractTools() {
		var registry = registry(Map.of("readonly", new CompositeSpec(List.of("alpha"), List.of("alpha_toolA"))));
		assertThat(registry.visibleToolNames(includes("readonly"))).containsExactly("alpha_toolB");
	}

	@Test
	void compositeNameWorksInExcludes() {
		var registry = registry(Map.of("all-alpha", new CompositeSpec(List.of("alpha"), List.of())));
		var visible = registry.visibleToolNames(ToolFilter.parse(Optional.empty(), Optional.of("[all-alpha]")));
		assertThat(visible).doesNotContain("alpha_toolA", "alpha_toolB");
		assertThat(visible).contains("beta_toolC", "hosty_toolD");
	}

	// ---- 验收 3：嵌套生效；循环定义被拒并友好报错 ----

	@Test
	void nestedCompositeResolves() {
		var registry = registry(Map.of(
				"inner", new CompositeSpec(List.of("alpha"), List.of()),
				"outer", new CompositeSpec(List.of("inner", "beta"), List.of())));
		assertThat(registry.visibleToolNames(includes("outer")))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
		assertThat(registry.visibleToolNames(includes("inner")))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void compositeExcludesCanReferenceAnotherComposite() {
		var registry = registry(Map.of(
				"base", new CompositeSpec(List.of("alpha"), List.of()),
				"outer", new CompositeSpec(List.of("alpha", "beta"), List.of("base"))));
		assertThat(registry.visibleToolNames(includes("outer"))).containsExactly("beta_toolC");
	}

	@Test
	void cycleBetweenCompositesIsRejectedWithFriendlyError() {
		assertThatThrownBy(() -> registry(Map.of(
				"a", new CompositeSpec(List.of("b"), List.of()),
				"b", new CompositeSpec(List.of("a"), List.of()))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("组合源循环引用")
			.hasMessageContaining("a");
	}

	@Test
	void selfCycleIsRejected() {
		assertThatThrownBy(() -> registry(Map.of("a", new CompositeSpec(List.of("a"), List.of()))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("组合源循环引用");
	}

	@Test
	void compositeNameCollidingWithBaseSourceIsRejected() {
		assertThatThrownBy(() -> registry(Map.of("alpha", new CompositeSpec(List.of("beta"), List.of()))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("冲突");
	}

	// ---- 组合源 scope 派生 ----

	@Test
	void compositeScopeIsHostWhenAllAggregatedToolsAreHost() {
		var registry = new McpSourceRegistry(List.of(new HostSource("toolD")),
				Map.of("host-view", new CompositeSpec(List.of("hosty"), List.of())));
		assertThat(compositeOf(registry).scope()).isEqualTo(Scope.HOST);
	}

	@Test
	void compositeScopeIsNetworkWhenMixedOrUnknownTools() {
		assertThat(compositeOf(registry(Map.of("mixed", new CompositeSpec(List.of("alpha", "hosty"), List.of())))).scope())
			.isEqualTo(Scope.NETWORK);
		// 全部未知项 → 空组合源按 network 处理
		assertThat(compositeOf(registry(Map.of("empty", new CompositeSpec(List.of("no_such_source"), List.of())))).scope())
			.isEqualTo(Scope.NETWORK);
	}

	// ---- 未知项静默忽略 + warn（不使解析失败） ----

	@Test
	void unknownItemsInCompositeAreIgnored() {
		var registry = registry(Map.of("weird",
				new CompositeSpec(List.of("no_such_source", "alpha"), List.of("no_such_tool"))));
		assertThat(registry.visibleToolNames(includes("weird")))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	// ---- 验收 4：无参数 / 未引用组合源时行为不受影响 ----

	@Test
	void emptySpecsDoNotPublishAnyCompositeAndKeepPlainBehavior() {
		var withEmpty = new McpSourceRegistry(List.of(new AlphaSource("toolA", "toolB")), Map.of());
		var plain = new McpSourceRegistry(List.of(new AlphaSource("toolA", "toolB")));
		assertThat(withEmpty.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("alpha");
		assertThat(withEmpty.sources().stream().noneMatch(s -> s.type() == SourceType.COMPOSITE)).isTrue();
		assertThat(withEmpty.allToolNames()).isEqualTo(plain.allToolNames());
		// 引用未定义组合源名 → 静默忽略 + warn，与未知源名行为一致（includes 只含未知项 → 空视图）
		assertThat(plain.visibleToolNames(includes("no_such_spec"))).isEmpty();
	}

}
