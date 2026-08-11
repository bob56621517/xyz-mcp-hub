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
 * 工具视图过滤纯逻辑单测（#51 严格语义）：URL 参数解析（{@link ToolFilter}，参数缺失 vs 显式空集）与
 * 源注册表解析（{@link McpSourceRegistry#visibleToolNames}，工具名通配、源名匹配退役），覆盖端到端
 * 测试不便表达的边界情况。
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
		public List<ToolCallback> getTools() {
			return tools;
		}

	}

	/** 假主机源（HostMcp）：目录元数据应派生为 type=native / scope=host（#50 host 并入 native）。 */
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

	// ---- ToolFilter.parse：参数缺失 vs 显式空集（#51 无语法糖） ----

	@Test
	void parseBracketsList() {
		var filter = ToolFilter.parse(Optional.of("[alpha_toolA,*toolC]"), Optional.of("[]"));
		assertThat(filter.includes()).contains(List.of("alpha_toolA", "*toolC"));
		assertThat(filter.excludes()).contains(List.of());
	}

	@Test
	void parseNoBracketsSingleItem() {
		var filter = ToolFilter.parse(Optional.of("alpha_toolA"), Optional.empty());
		assertThat(filter.includes()).contains(List.of("alpha_toolA"));
		assertThat(filter.excludes()).isEmpty();
	}

	@Test
	void missingParamsAreAbsent() {
		// 参数缺失 → includes/excludes 均为 Optional.empty()（缺失 ≡ 全量 / 不减）
		var filter = ToolFilter.parse(Optional.empty(), Optional.empty());
		assertThat(filter.includes()).isEmpty();
		assertThat(filter.excludes()).isEmpty();
		assertThat(filter.hasExplicitFilter()).isFalse();
		assertThat(filter.includesAbsent()).isTrue();
	}

	@Test
	void explicitEmptyBracketIsPresentEmptyList() {
		// includes=[]：显式空集（有参数），与「参数缺失」严格区分——#51 无语法糖的核心
		var filter = ToolFilter.parse(Optional.of("[]"), Optional.of(""));
		assertThat(filter.includes()).contains(List.of());
		assertThat(filter.excludes()).contains(List.of());
		assertThat(filter.hasExplicitFilter()).isTrue();
		assertThat(filter.includesAbsent()).isFalse();
	}

	@Test
	void parseTrimsWhitespace() {
		var filter = ToolFilter.parse(Optional.of("[ alpha_toolA , alpha_toolB ]"), Optional.empty());
		assertThat(filter.includes()).contains(List.of("alpha_toolA", "alpha_toolB"));
	}

	// ---- McpSourceRegistry.visibleToolNames（#51 严格语义：通配 + 源名退役） ----

	@Test
	void noParamsIsAllTools() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.ALL))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
	}

	@Test
	void explicitEmptyIncludesIsEmptyToolSet() {
		// includes=[] = 空集（无语法糖），不引入任何工具——与「参数缺失 = 全量」严格区分
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[]"), Optional.empty())))
			.isEmpty();
	}

	@Test
	void bareWildcardIsAllTools() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[*]"), Optional.empty())))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
	}

	@Test
	void prefixWildcardSelectsSourceTools() {
		// 源名匹配退役：要某源全部工具写 源名*（替代旧的源名展开）
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha*]"), Optional.empty())))
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void suffixWildcardMatches() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[*toolC]"), Optional.empty())))
			.containsExactly("beta_toolC");
	}

	@Test
	void middleWildcardMatches() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha_*B]"), Optional.empty())))
			.containsExactly("alpha_toolB");
	}

	@Test
	void exactToolNameSelectsSingle() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha_toolB]"), Optional.empty())))
			.containsExactly("alpha_toolB");
	}

	@Test
	void wildcardInExcludesSubtractsFromAll() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		Set<String> visible = registry.visibleToolNames(ToolFilter.parse(Optional.empty(), Optional.of("[alpha*]")));
		assertThat(visible).containsExactly("beta_toolC");
	}

	@Test
	void explicitEmptyExcludesSubtractsNothing() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		Set<String> visible = registry.visibleToolNames(ToolFilter.parse(Optional.empty(), Optional.of("[]")));
		assertThat(visible).containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
	}

	@Test
	void questionMarkIsLiteralNotWildcard() {
		// ? 不支持：按字面处理，工具名不含 ? → 匹配不到 → 空集 + warn
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha_tool?]"), Optional.empty())))
			.isEmpty();
	}

	@Test
	void sourceNameIsNoLongerExpanded() {
		// 源名匹配退役（#51）：includes=[alpha]（无星号）不再展开 alpha 源，按未知项忽略 → 空集
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha]"), Optional.empty())))
			.isEmpty();
	}

	@Test
	void unknownWildcardItemIsIgnoredWithoutThrowing() {
		// 通配项匹配不到任何工具 → 未知项 warn，不使连接失败，剩余项照常生效
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		Set<String> visible = registry.visibleToolNames(
			ToolFilter.parse(Optional.of("[no_such*,alpha*,no_such_tool*]"), Optional.empty()));
		assertThat(visible).containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void wildcardInExcludesMatchingNothingSubtractsNothing() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		// excludes 通配匹配不到任何工具 → 未知项 warn，不减任何工具
		Set<String> visible = registry.visibleToolNames(ToolFilter.parse(Optional.empty(), Optional.of("[*search]")));
		assertThat(visible).containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB", "beta_toolC");
	}

	@Test
	void visibilityCheckRespectsFilter() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		ToolFilter onlyAlpha = ToolFilter.parse(Optional.of("[alpha*]"), Optional.empty());
		assertThat(registry.isVisible("alpha_toolA", onlyAlpha)).isTrue();
		assertThat(registry.isVisible("beta_toolC", onlyAlpha)).isFalse();
		assertThat(registry.isVisible("beta_toolC", ToolFilter.ALL)).isTrue();
	}

	@Test
	void disabledProviderIsRegisteredButNotEnabled() {
		// #50 注册/启用分离：未启用源仍注册（目录列出、enabled=false），工具不进全量表
		McpEndpointProvider alpha = new AlphaSource("toolA", "toolB") {
			@Override
			public boolean isEnabled() {
				return false;
			}
		};
		McpSourceRegistry registry = new McpSourceRegistry(List.of(alpha));
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("alpha");
		assertThat(registry.sources().get(0).enabled()).isFalse();
		assertThat(registry.allToolNames()).isEmpty();
		assertThat(registry.visibleToolNames(ToolFilter.ALL)).isEmpty();
	}

	@Test
	void disabledProviderReferencedByUrlYieldsEmptySetWithWarn() {
		McpEndpointProvider alpha = new AlphaSource("toolA", "toolB") {
			@Override
			public boolean isEnabled() {
				return false;
			}
		};
		McpSourceRegistry registry = new McpSourceRegistry(List.of(alpha));
		// 未启用源 specs 为空：通配 [alpha*] 匹配不到任何工具 → 未知项 warn + 空集，连接不失败
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[alpha*]"), Optional.empty())))
			.isEmpty();
	}

	// ---- 目录元数据（issue #34）：McpSource 携带 type/protocol/scope ----

	@Test
	void nativeSourceCarriesCatalogMetadata() {
		McpSourceRegistry registry = registryWithAlphaAndBeta();
		McpSourceRegistry.McpSource alpha = registry.sources().get(0);
		assertThat(alpha.name()).isEqualTo("alpha");
		assertThat(alpha.type()).isEqualTo(SourceType.NATIVE);
		assertThat(alpha.scope()).isEqualTo(Scope.NETWORK);
		assertThat(alpha.protocol()).isNull();
		assertThat(alpha.specs()).extracting(spec -> spec.tool().name())
			.containsExactlyInAnyOrder("alpha_toolA", "alpha_toolB");
	}

	@Test
	void hostSourceIsNativeTypeWithHostScope() {
		// #50 host 并入 native：type=native，scope=host 表达部署
		McpSourceRegistry registry = new McpSourceRegistry(List.of(new HostSource("toolA")));
		McpSourceRegistry.McpSource host = registry.sources().get(0);
		assertThat(host.type()).isEqualTo(SourceType.NATIVE);
		assertThat(host.scope()).isEqualTo(Scope.HOST);
		assertThat(host.enabled()).isTrue();
		assertThat(host.protocol()).isNull();
		assertThat(host.specs()).extracting(spec -> spec.tool().name()).containsExactly("hosty_toolA");
	}

}
