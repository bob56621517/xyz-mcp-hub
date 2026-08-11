package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.TestProxyMcpProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ProxyMcp 源注册纯逻辑单测（#35）：注册/启用分离（#50，未启用源仍注册、目录列出 enabled=false）、
 * proxy 工具规格 {@code {source}_{tool}} 前缀改名、close 幂等。不启动 Spring 上下文、不触网。
 * #52 起用 {@link TestProxyMcpProvider} 夹具替代已删除的具体 Provider 类。
 */
class McpProxySourceRegistryUnitTest {

	/** 固定规格的假上游工具（hello，可被 {@link TestProxyMcpProvider} 的 discoverer 返回）。 */
	private static List<McpServerFeatures.AsyncToolSpecification> helloSpec() {
		var tool = McpSchema.Tool.builder()
			.name("hello")
			.description("假上游工具")
			.inputSchema(McpSchema.JsonSchema.builder().type("object").additionalProperties(false).build())
			.build();
		return List.of(new McpServerFeatures.AsyncToolSpecification(tool,
				(exchange, request) -> Mono.just(McpSchema.CallToolResult.builder()
					.content(List.of(new McpSchema.TextContent("pong")))
					.build())));
	}

	/** 假原生源：单个工具，与 proxy 混跑验证互不干扰。 */
	private static class NativeSource extends NativeMcp {

		NativeSource() {
			super(Scope.NETWORK);
		}

		@Override
		public String getName() {
			return "native";
		}

		@Override
		public List<ToolCallback> getTools() {
			return List.of(MethodToolCallbackProvider.builder().toolObjects(new FakeTools()).build()
				.getToolCallbacks());
		}

	}

	static final class FakeTools {

		@org.springframework.ai.tool.annotation.Tool(description = "native a")
		public String toolA() {
			return "a";
		}

	}

	@Test
	void unreachableProxySourceIsRegisteredWithEmptyToolsWithoutThrowing() {
		// #50 注册/启用分离：proxy 上游不可达源仍注册（enabled 保持配置值 true）、工具为空，不抛
		McpSourceRegistry registry = new McpSourceRegistry(
				List.of(new TestProxyMcpProvider("unreachable", "http://localhost:1/x")));
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("unreachable");
		assertThat(registry.sources().get(0).enabled()).isTrue();
		assertThat(registry.sources().get(0).specs()).isEmpty();
		assertThat(registry.allToolNames()).isEmpty();
	}

	@Test
	void unreachableProxySourceKeepsOtherSourcesIntact() {
		McpSourceRegistry registry = new McpSourceRegistry(
				List.of(new TestProxyMcpProvider("unreachable", "http://localhost:1/x"), new NativeSource()));
		// proxy 源注册但工具为空，原生源不受影响
		assertThat(registry.allToolNames()).containsExactly("native_toolA");
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name)
			.containsExactlyInAnyOrder("unreachable", "native");
		// 工具为空的 proxy 源被通配引用得空集（匹配不到任何工具 + warn），连接不失败
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[unreachable*]"), Optional.empty())))
			.isEmpty();
	}

	@Test
	void disabledProxySourceIsRegisteredButNotEnabled() {
		// 未启用 proxy（isEnabled=false，如缺 token/认证）：目录列出 enabled=false、工具为空，不触上游
		McpEndpointProvider disabled = new TestProxyMcpProvider(
				"fake-proxy", "http://localhost:1/never", Map.of(), List.of(), false, null);
		McpSourceRegistry registry = new McpSourceRegistry(List.of(disabled));
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("fake-proxy");
		assertThat(registry.sources().get(0).enabled()).isFalse();
		assertThat(registry.allToolNames()).isEmpty();
	}

	@Test
	void proxySpecsAreRenamedWithSourcePrefix() {
		TestProxyMcpProvider proxy = new TestProxyMcpProvider(
				"fake-proxy", "http://localhost:1/never", Map.of(), List.of(), true, McpProxySourceRegistryUnitTest::helloSpec);
		McpSourceRegistry registry = new McpSourceRegistry(List.of(proxy));
		// 上游工具 hello → {source}_{tool} = fake_proxy_hello（源名连字符归一化为下划线）
		assertThat(registry.allToolNames()).containsExactly("fake_proxy_hello");
		var spec = registry.specByName("fake_proxy_hello");
		assertThat(spec).isPresent();
		assertThat(spec.get().tool().description()).isEqualTo("假上游工具");
		// 源名匹配退役（#51）：要该源全部工具写归一化前缀通配 [fake_proxy*]
		// （连字符源名 fake-proxy 归一化为下划线 fake_proxy，见 prefixedToolName）
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[fake_proxy*]"), Optional.empty())))
			.containsExactly("fake_proxy_hello");
	}

	@Test
	void closeReleasesProxyUpstreamAndIsIdempotent() {
		TestProxyMcpProvider proxy = new TestProxyMcpProvider(
				"fake-proxy", "http://localhost:1/never", Map.of(), List.of(), true, McpProxySourceRegistryUnitTest::helloSpec);
		McpSourceRegistry registry = new McpSourceRegistry(List.of(proxy, new NativeSource()));
		assertThatCode(registry::close).doesNotThrowAnyException();
		assertThat(proxy.closeCalls()).isEqualTo(1);
		assertThatCode(registry::close).doesNotThrowAnyException();
	}

}
