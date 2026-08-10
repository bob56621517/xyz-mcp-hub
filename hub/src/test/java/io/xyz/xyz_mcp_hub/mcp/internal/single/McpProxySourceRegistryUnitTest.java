package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;
import java.util.Optional;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import io.xyz.xyz_mcp_hub.mcp.internal.proxy.ProxyMcpProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ProxyMcp 源注册纯逻辑单测（#35）：源降级（上游不可达不入注册表）、proxy 工具规格
 * {@code {source}_{tool}} 前缀改名、close 幂等。不启动 Spring 上下文、不触网。
 */
class McpProxySourceRegistryUnitTest {

	/** 指向不可达地址的 proxy 源：discoverTools 走真实 connect 必失败（Connection refused）。 */
	private static class UnreachableProxy extends ProxyMcpProvider {

		private final String upstreamUrl;

		UnreachableProxy(String upstreamUrl) {
			this.upstreamUrl = upstreamUrl;
		}

		@Override
		public String getName() {
			return "unreachable";
		}

		@Override
		public String getPath() {
			return "/mcp/builtin/unreachable";
		}

		@Override
		public String getUpstreamUrl() {
			return upstreamUrl;
		}

	}

	/** 假 proxy 源：覆写 discoverTools 返回固定工具规格，不触网。 */
	private static class FakeProxy extends ProxyMcpProvider {

		private int closeCalls = 0;

		@Override
		public String getName() {
			return "fake-proxy";
		}

		@Override
		public String getPath() {
			return "/mcp/builtin/fake-proxy";
		}

		@Override
		public String getUpstreamUrl() {
			return "http://localhost:1/never";
		}

		@Override
		public List<McpServerFeatures.AsyncToolSpecification> discoverTools() {
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

		@Override
		public void close() {
			closeCalls++;
		}

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
		public String getPath() {
			return "/mcp/builtin/native";
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
	void unreachableProxySourceIsDegradedWithoutThrowing() {
		McpSourceRegistry registry = new McpSourceRegistry(List.of(new UnreachableProxy("http://localhost:1/x")));
		assertThat(registry.sources()).isEmpty();
		assertThat(registry.allToolNames()).isEmpty();
	}

	@Test
	void degradedProxyKeepsOtherSourcesIntact() {
		McpSourceRegistry registry = new McpSourceRegistry(
				List.of(new UnreachableProxy("http://localhost:1/x"), new NativeSource()));
		// proxy 源被降级，原生源不受影响
		assertThat(registry.allToolNames()).containsExactly("native_toolA");
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("native");
	}

	@Test
	void proxySpecsAreRenamedWithSourcePrefix() {
		FakeProxy proxy = new FakeProxy();
		McpSourceRegistry registry = new McpSourceRegistry(List.of(proxy));
		// 上游工具 hello → {source}_{tool} = fake_proxy_hello（源名连字符归一化为下划线）
		assertThat(registry.allToolNames()).containsExactly("fake_proxy_hello");
		var spec = registry.specByName("fake_proxy_hello");
		assertThat(spec).isPresent();
		assertThat(spec.get().tool().description()).isEqualTo("假上游工具");
		// 源名可展开该源全部工具（URL 参数语法）
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[fake-proxy]"), Optional.empty())))
			.containsExactly("fake_proxy_hello");
	}

	@Test
	void closeReleasesProxyUpstreamAndIsIdempotent() {
		FakeProxy proxy = new FakeProxy();
		McpSourceRegistry registry = new McpSourceRegistry(List.of(proxy, new NativeSource()));
		assertThatCode(registry::close).doesNotThrowAnyException();
		assertThat(proxy.closeCalls).isEqualTo(1);
		assertThatCode(registry::close).doesNotThrowAnyException();
	}

}
