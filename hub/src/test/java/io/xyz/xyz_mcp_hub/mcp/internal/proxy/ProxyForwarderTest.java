package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.xyz.mcp.testproxy.UpstreamMcpApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通用转发器机制测试（#52，ADR-0007 决策 4）：内嵌 mock 上游 MCP Server
 * （{@code UpstreamMcpApplication}，工具 echo / fail）验证配置驱动的
 * {@link ConfigProxyMcpProvider} 的 discoverTools / selectTools / callTool 透传 / 认证 /
 * 降级，以及 hook 可扩展点（工具名映射 / 错误处理）。
 *
 * <p>无外部依赖：内嵌上游模拟，不触网、不需真实 key；不启动 Hub 应用上下文（provider 直连内嵌上游）。</p>
 */
class ProxyForwarderTest {

	private static ConfigurableApplicationContext upstreamContext;
	private static String upstreamUrl;

	@BeforeAll
	static void startUpstream() {
		upstreamContext = new SpringApplicationBuilder(UpstreamMcpApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		int port = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
		upstreamUrl = "http://localhost:" + port + "/mcp/server/upstream";
	}

	@AfterAll
	static void stopUpstream() {
		if (upstreamContext != null) {
			upstreamContext.close();
		}
	}

	private static ConfigProxyMcpProvider provider(String name, String upstreamUrl) {
		return new ConfigProxyMcpProvider(new ProxySourceConfig(name, upstreamUrl, null, null, null));
	}

	private static ConfigProxyMcpProvider provider(String name, String upstreamUrl, List<String> toolsSubset) {
		return new ConfigProxyMcpProvider(new ProxySourceConfig(name, upstreamUrl, null, toolsSubset, null));
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	// ---- discoverTools：启动时向 mock 上游 listTools 发现并缓存 ----

	@Test
	void discoverToolsDiscoversUpstreamTools() {
		ConfigProxyMcpProvider p = provider("context7", upstreamUrl);
		try {
			List<McpServerFeatures.AsyncToolSpecification> specs = p.discoverTools();
			assertThat(specs).extracting(s -> s.tool().name())
				.containsExactlyInAnyOrder("echo", "fail");
		}
		finally {
			p.close();
		}
	}

	// ---- selectTools / tools-subset：固定工具子集过滤 ----

	@Test
	void toolsSubsetFiltersDiscoveredTools() {
		ConfigProxyMcpProvider p = provider("sub", upstreamUrl, List.of("echo"));
		try {
			assertThat(p.discoverTools()).extracting(s -> s.tool().name()).containsExactly("echo");
		}
		finally {
			p.close();
		}
	}

	@Test
	void selectToolsKeepsOnlySubset() {
		ConfigProxyMcpProvider p = provider("sub", upstreamUrl, List.of("echo"));
		var all = List.of(
				McpSchema.Tool.builder().name("echo").description("回显")
					.inputSchema(McpSchema.JsonSchema.builder().type("object").additionalProperties(false).build()).build(),
				McpSchema.Tool.builder().name("fail").description("失败")
					.inputSchema(McpSchema.JsonSchema.builder().type("object").additionalProperties(false).build()).build());
		assertThat(p.selectTools(all)).extracting(McpSchema.Tool::name).containsExactly("echo");
	}

	// ---- callTool 透传：spec callHandler 透明转发到 mock 上游，响应原样返回 ----

	@Test
	void callToolForwardsToUpstream() {
		ConfigProxyMcpProvider p = provider("context7", upstreamUrl);
		try {
			McpServerFeatures.AsyncToolSpecification echo = p.discoverTools().stream()
				.filter(s -> s.tool().name().equals("echo"))
				.findFirst()
				.orElseThrow();
			var result = echo.callHandler().apply(null,
					McpSchema.CallToolRequest.builder("echo").arguments(Map.of("message", "你好")).build()).block();
			assertThat(result.isError()).isFalse();
			assertThat(text(result)).isEqualTo("echo: 你好");
		}
		finally {
			p.close();
		}
	}

	@Test
	void upstreamErrorIsPropagated() {
		ConfigProxyMcpProvider p = provider("context7", upstreamUrl);
		try {
			McpServerFeatures.AsyncToolSpecification fail = p.discoverTools().stream()
				.filter(s -> s.tool().name().equals("fail"))
				.findFirst()
				.orElseThrow();
			var result = fail.callHandler().apply(null,
					McpSchema.CallToolRequest.builder("fail").arguments(Map.of()).build()).block();
			assertThat(result.isError()).isTrue();
			assertThat(text(result)).isEqualTo("上游模拟失败");
		}
		finally {
			p.close();
		}
	}

	// ---- hook 可扩展点（ADR-0007 决策 3）：工具名映射 / 错误处理 ----

	@Test
	void mapToolNameHookRemapsForwardedTool() {
		ProxyHooks hooks = new ProxyHooks() {
			@Override
			public String mapToolName(String upstreamToolName) {
				return upstreamToolName.equals("echo") ? "fail" : upstreamToolName;
			}
		};
		ConfigProxyMcpProvider p = new ConfigProxyMcpProvider(
				new ProxySourceConfig("mapped", upstreamUrl, null, null, null), hooks);
		try {
			McpServerFeatures.AsyncToolSpecification echo = p.discoverTools().stream()
				.filter(s -> s.tool().name().equals("echo"))
				.findFirst()
				.orElseThrow();
			// echo → mapToolName → fail：转发到上游 fail 工具（isError 透传）
			var result = echo.callHandler().apply(null,
					McpSchema.CallToolRequest.builder("echo").arguments(Map.of("message", "hi")).build()).block();
			assertThat(result.isError()).isTrue();
		}
		finally {
			p.close();
		}
	}

	@Test
	void handleCallErrorHookIsWiredFromHooks() {
		ProxyHooks hooks = new ProxyHooks() {
			@Override
			public McpSchema.CallToolResult handleCallError(McpSchema.CallToolRequest request, RuntimeException error) {
				return McpSchema.CallToolResult.builder()
					.content(List.of(new McpSchema.TextContent("handled: " + error.getMessage())))
					.build();
			}
		};
		ConfigProxyMcpProvider p = new ConfigProxyMcpProvider(
				new ProxySourceConfig("err", upstreamUrl, null, null, null), hooks);
		var result = p.handleCallError(McpSchema.CallToolRequest.builder("echo").build(), new RuntimeException("boom"));
		assertThat(result.isError()).isFalse();
		assertThat(text(result)).isEqualTo("handled: boom");
	}

	@Test
	void defaultHandleCallErrorRethrows() {
		ConfigProxyMcpProvider p = provider("err", upstreamUrl);
		assertThatThrownBy(() -> p.handleCallError(
				McpSchema.CallToolRequest.builder("echo").build(), new RuntimeException("boom")))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("boom");
	}

	// ---- ProxySourceFactory + ProxyHooksCustomizer：hook 定制经工厂装配（不新增 Provider 类）----

	@Test
	void proxySourceFactoryAppliesHooksCustomizer() {
		ProxySourceConfig config = new ProxySourceConfig("custom", upstreamUrl, "Authorization: Bearer secret", null, null);
		// 定制器装饰配置推导的 hooks：只覆盖 mapToolName，其余委托 base（保留配置认证）
		ProxyHooksCustomizer customizer = (cfg, base) -> new ProxyHooks() {
			@Override
			public Map<String, String> authHeaders() {
				return base.authHeaders();
			}

			@Override
			public List<String> toolSubset() {
				return base.toolSubset();
			}

			@Override
			public String mapToolName(String upstreamToolName) {
				return upstreamToolName.equals("echo") ? "fail" : upstreamToolName;
			}
		};
		ProxySourceFactory factory = new ProxySourceFactory(new ProxyProperties(List.of(config)), List.of(customizer));
		ConfigProxyMcpProvider p = factory.buildProvider(config);
		// 定制器生效：mapToolName 被覆盖
		assertThat(p.mapToolName("echo")).isEqualTo("fail");
		assertThat(p.mapToolName("other")).isEqualTo("other");
		// 未定制部分回退配置推导：认证 header 与 enabled 门控保留
		assertThat(p.getAuthHeaders()).containsEntry("Authorization", "Bearer secret");
		assertThat(p.isEnabled()).isTrue();
	}

	// ---- 认证：auth-header 解析 + enabled 门控 ----

	@Test
	void authHeadersParsedFromConfig() {
		assertThat(ProxyHooks.parseAuthHeader("Authorization: Bearer xyz"))
			.containsExactly(Map.entry("Authorization", "Bearer xyz"));
		// 裸值自动补 Authorization 名
		assertThat(ProxyHooks.parseAuthHeader("Bearer xyz"))
			.containsExactly(Map.entry("Authorization", "Bearer xyz"));
		assertThat(ProxyHooks.parseAuthHeader("  ")).isEmpty();
		assertThat(ProxyHooks.parseAuthHeader(null)).isEmpty();
	}

	@Test
	void enabledGatingFromConfig() {
		// 无 auth-header → 默认启用（公开代理）
		assertThat(provider("p", upstreamUrl).isEnabled()).isTrue();
		// auth-header 空白 → 未启用（注册/启用分离）
		assertThat(new ConfigProxyMcpProvider(new ProxySourceConfig("p", upstreamUrl, "", null, null)).isEnabled())
			.isFalse();
		// 显式 enabled 覆盖自动推导
		assertThat(new ConfigProxyMcpProvider(new ProxySourceConfig("p", upstreamUrl, null, null, false)).isEnabled())
			.isFalse();
		assertThat(new ConfigProxyMcpProvider(new ProxySourceConfig("p", upstreamUrl, "", null, true)).isEnabled())
			.isTrue();
		// 配置但解析不出有效 header（空 header 名）→ 未启用，避免「enabled 但发不出认证头」静默鉴权失效
		assertThat(new ConfigProxyMcpProvider(new ProxySourceConfig("p", upstreamUrl, ": Bearer x", null, null)).isEnabled())
			.isFalse();
	}

	// ---- 降级：上游不可达 → discoverTools 抛异常（源注册表兜底降级） ----

	@Test
	void unreachableUpstreamDegradesDiscoverTools() {
		ConfigProxyMcpProvider p = provider("bad", "http://localhost:1/mcp");
		assertThatThrownBy(p::discoverTools).isInstanceOf(RuntimeException.class);
	}

}
