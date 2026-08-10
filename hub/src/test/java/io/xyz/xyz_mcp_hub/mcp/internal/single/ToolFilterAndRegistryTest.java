package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host.HostMcp;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具视图过滤纯逻辑单测：URL 参数解析（{@link ToolFilter}）与源注册表解析
 * （{@link McpSourceRegistry#visibleToolNames}），覆盖端到端测试不便表达的边界情况。
 *
 * <p>无外部依赖（不启动 Spring 上下文，用假 {@link NativeMcp} provider）。</p>
 */
class ToolFilterAndRegistryTest {

	/** 假原生源：带两个工具的 alpha 源。 */
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
		public String getPath() {
			return "/mcp/builtin/alpha";
		}

		@Override
		public List<ToolCallback> getTools() {
			return tools;
		}

	}

	/** 假原生源：单个工具的 beta 源。 */
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
		public String getPath() {
			return "/mcp/builtin/beta";
		}

		@Override
		public List<ToolCallback> getTools() {
			return tools;
		}

	}

	/** 假主机源（HostMcp）：目录元数据应派生为 type=host / scope=host。 */
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
		public String getPath() {
			return "/mcp/builtin/hosty";
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

	}

	private static List<ToolCallback> toolCallbacks(String... names) {
		return List.of(MethodToolCallbackProvider.builder().toolObjects(new FakeTools()).build().getToolCallbacks())
			.stream()
			.filter(callback -> names.length == 0 || List.of(names).contains(callback.getToolDefinition().name()))
			.toList();
	}

	private McpSourceRegistry registryWithAlphaAndBeta() {
		// toolA/toolB 属于 alpha，toolC 属于 beta
		McpEndpointProvider alpha = new AlphaSource("toolA", "toolB");
		McpEndpointProvider beta = new BetaSource("toolC");
		return new McpSourceRegistry(List.of(alpha, beta));
	}

	// ---- ToolFilter.parse ----

	@Test
	void parseBracketsList() {
		var filter = ToolFilter.parse(Optional.of("[alpha,toolA]"), Optional.of("[]"));
		assertThat(filter.includes()).containsExactly("alpha", "toolA");
		assertThat(filter.excludes()).isEmpty();
	}

	@Test
	void parseNoBracketsSingleItem() {
		var filter = ToolFilter.parse(Optional.of("alpha"), Optional.empty());
		assertThat(filter.includes()).containsExactly("alpha");
		assertThat(filter.excludes()).isEmpty();
	}

	@Test
	void parseEmptyAndMissingAreEmpty() {
		assertThat(ToolFilter.parse(Optional.empty(), Optional.empty()).isEmpty()).isTrue();
		assertThat(ToolFilter.parse(Optional.of("[]"), Optional.of("")).isEmpty()).isTrue();
	}

	@Test
	void parseTrimsWhitespace() {
		var filter = ToolFilter.parse(Optional.of("[ alpha , toolA ]"), Optional.empty());
		assertThat(filter.includes()).containsExactly("alpha", "toolA");
	}

	// ---- McpSourceRegistry.visibleToolNames ----

	@Test
	void noFilterIsAllTools() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.EMPTY))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
	}

	@Test
	void sourceNameExpandsAllItsTools() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha]"), Optional.empty())))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void exactToolNameSelectsSingle() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha_toolB]"), Optional.empty())))
			.containsExactly("alpha_toolB");
	}

	@Test
	void excludesSubtractFromAllWhenIncludesEmpty() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		Set<String> visible = registry.visibleToolNames(ToolFilter.parse(Optional.empty(), Optional.of("[beta]")));
		assertThat(visible).containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void sourceNamePrefixIsNotConfusedWithExactTool() {
		// 源名 alpha 展开的是 alpha_*；若某工具名恰好是 alpha 本身（无下划线），不会被源展开误伤
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		Set<String> visible = registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha,beta_toolC]"), Optional.empty()));
		assertThat(visible).containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
	}

	@Test
	void unknownItemsAreIgnoredWithoutThrowing() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		Set<String> visible = registry.visibleToolNames(
				ToolFilter.parse(Optional.of("[no_such_source,alpha,no_such_tool]"), Optional.empty()));
		assertThat(visible).containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void visibilityCheckRespectsFilter() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		ToolFilter onlyAlpha = ToolFilter.parse(Optional.of("[alpha]"), Optional.empty());
		assertThat(registry.isVisible("alpha_toolA", onlyAlpha)).isTrue();
		assertThat(registry.isVisible("beta_toolC", onlyAlpha)).isFalse();
		assertThat(registry.isVisible("beta_toolC", ToolFilter.EMPTY)).isTrue();
	}

	@Test
	void disabledProviderIsExcludedFromRegistry() {
		McpEndpointProvider alpha = new AlphaSource("toolA", "toolB") {
			@Override
			public boolean isEnabled() {
				return false;
			}
		};
		McpSourceRegistry registry = new McpSourceRegistry(List.of(alpha));
		assertThat(registry.allToolNames()).isEmpty();
	}

	// ---- 目录元数据（issue #34）：McpSource 携带 type/protocol/scope/base ----

	@Test
	void nativeSourceCarriesCatalogMetadata() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		McpSourceRegistry.McpSource alpha = registry.sources().get(0);
		assertThat(alpha.name()).isEqualTo("alpha");
		assertThat(alpha.type()).isEqualTo(SourceType.NATIVE);
		assertThat(alpha.scope()).isEqualTo(Scope.NETWORK);
		assertThat(alpha.protocol()).isNull();
		assertThat(alpha.base()).isNull();
		assertThat(alpha.specs()).extracting(spec -> spec.tool().name())
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void hostSourceIsTypedHostWithHostScope() {
		McpSourceRegistry registry = new McpSourceRegistry(List.of(new HostSource("toolA")));
		McpSourceRegistry.McpSource host = registry.sources().get(0);
		assertThat(host.type()).isEqualTo(SourceType.HOST);
		assertThat(host.scope()).isEqualTo(Scope.HOST);
		assertThat(host.protocol()).isNull();
		assertThat(host.base()).isNull();
		assertThat(host.specs()).extracting(spec -> spec.tool().name()).containsExactly("hosty_toolA");
	}

}
