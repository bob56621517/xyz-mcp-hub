package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.xyz.mcp.testproxy.MarkitdownUpstreamApplication;
import io.xyz.mcp.testproxy.MarkitdownUpstreamRegistrar;
import io.xyz.xyz_mcp_hub.docker.ContainerEndpoint;
import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.mcp.internal.single.McpSourceRegistry;
import io.xyz.xyz_mcp_hub.mcp.internal.single.ToolFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * ContainerMcp markitdown 源单测（#37，次 seam A）：注入 fake {@link ContainerManager} 与指向内嵌
 * 模拟上游的 {@link ContainerEndpoint}，不启真实 docker、不启 hub Spring 上下文。
 *
 * <p>覆盖 #37 验收：includes 源展开暴露 {@code markitdown_convert_to_markdown}（静态冒烟清单）、工具
 * 调用转发到容器内 MCP 端点（内嵌上游记录收到的 uri）、SSRF 预检拦截内网地址、容器启动失败/上游不可达
 * 时源降级返回友好文本、docker 运行时缺失/清单缺失时 isEnabled=false。</p>
 */
class MarkitdownContainerMcpTest {

	private static ConfigurableApplicationContext upstreamContext;
	private static int upstreamPort;

	@TempDir
	static Path tempDir;

	private ContainerSpec spec;
	private ContainerSpecReader specReader;
	private ContainerManager fakeManager;

	@BeforeAll
	static void startUpstream() {
		upstreamContext = new SpringApplicationBuilder(MarkitdownUpstreamApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
	}

	@AfterAll
	static void stopUpstream() {
		if (upstreamContext != null) {
			upstreamContext.close();
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		Path manifest = tempDir.resolve("mcp-images.yaml");
		Files.writeString(manifest, """
			images:
			  markitdown:
			    image: xyz-mcp-hub/markitdown:latest
			    protocol: mcp
			    port: 3001
			    hostPort: 13001
			""", StandardCharsets.UTF_8);
		specReader = new ContainerSpecReader(manifest);
		spec = specReader.byName("markitdown").orElseThrow();
		fakeManager = mock(ContainerManager.class);
		when(fakeManager.ensureRunning(any(ContainerSpec.class)))
			.thenReturn(new ContainerHandle(spec, "cid-fake"));
		MarkitdownUpstreamRegistrar.LAST_URI.set(null);
	}

	/** 指向内嵌模拟上游的端点（测试注入，替代默认 127.0.0.1:hostPort）。 */
	private ContainerEndpoint embeddedUpstream() {
		return ignored -> "http://localhost:" + upstreamPort + "/mcp/server/markitdown";
	}

	private MarkitdownContainerMcp provider(ContainerManager manager, ContainerEndpoint endpoint) {
		return new MarkitdownContainerMcp(manager, specReader, endpoint);
	}

	private ToolCallback convertTool() {
		return provider(fakeManager, embeddedUpstream()).getTools().get(0);
	}

	// ---- 验收 1：includes=markitdown 暴露 convert_to_markdown（静态冒烟清单） ----

	@Test
	void sourceExposesPrefixedConvertToolInRegistry() {
		McpSourceRegistry registry = new McpSourceRegistry(
			java.util.List.of(provider(fakeManager, embeddedUpstream())));
		assertThat(registry.allToolNames()).containsExactly("markitdown_convert_to_markdown");
	}

	@Test
	void includesSourceNameExpandsToConvertTool() {
		McpSourceRegistry registry = new McpSourceRegistry(
			java.util.List.of(provider(fakeManager, embeddedUpstream())));
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[markitdown]"), Optional.empty())))
			.containsExactly("markitdown_convert_to_markdown");
	}

	// ---- 验收 2：工具调用转发到容器内 MCP 端点（首用拉起） ----

	@Test
	void toolCallForwardsUriToContainerAndReturnsMarkdown() {
		// @Tool 返回 String 会被 Spring AI 结果转换器 JSON 编码（全库既有行为，fetch 同），用 contains 断言
		String result = convertTool().call("{\"uri\":\"https://example.com/page\"}");
		assertThat(result).contains("mock 转换结果");
		assertThat(MarkitdownUpstreamRegistrar.LAST_URI.get()).isEqualTo("https://example.com/page");
		// 首用拉起：ensureRunning 被调用（工具调用一次 + 客户端重试窗口 touch 一次，故至少一次）
		verify(fakeManager, atLeastOnce()).ensureRunning(spec);
	}

	@Test
	void dataUriIsForwardedWithoutSsrPrecheck() {
		String result = convertTool().call("{\"uri\":\"data:text/plain,hello\"}");
		assertThat(result).contains("mock 转换结果");
		assertThat(MarkitdownUpstreamRegistrar.LAST_URI.get()).isEqualTo("data:text/plain,hello");
	}

	// ---- 验收：SSRF 预检（http(s) uri 交给容器前拦截内网/保留段） ----

	@Test
	void ssrfRejectsPrivateUrlBeforeForwarding() {
		String result = convertTool().call("{\"uri\":\"http://127.0.0.1:8080/internal\"}");
		assertThat(result).contains("SSRF 防护拦截");
		// 内网地址被拒：不转发到容器
		assertThat(MarkitdownUpstreamRegistrar.LAST_URI.get()).isNull();
		verify(fakeManager, never()).ensureRunning(any(ContainerSpec.class));
	}

	// ---- 验收：容器启动失败 / 上游不可达 → 源降级、返回友好文本 ----

	@Test
	void containerStartFailureReturnsFriendlyError() {
		when(fakeManager.ensureRunning(any(ContainerSpec.class)))
			.thenThrow(new IllegalStateException("docker daemon 不可用"));
		String result = convertTool().call("{\"uri\":\"https://example.com/x\"}");
		assertThat(result).contains("markitdown 容器不可用").contains("docker daemon 不可用");
	}

	@Test
	void upstreamUnreachableReturnsFriendlyError() {
		ContainerEndpoint unreachable = ignored -> "http://localhost:1/mcp";
		ToolCallback tool = provider(fakeManager, unreachable).getTools().get(0);
		String result = tool.call("{\"uri\":\"https://example.com/x\"}");
		assertThat(result).contains("markitdown 容器不可用");
	}

	// ---- 验收：无 docker 运行时 / 清单缺失 → isEnabled=false 降级 ----

	@Test
	void disabledWhenContainerRuntimeAbsent() {
		assertThat(provider(null, embeddedUpstream()).isEnabled()).isFalse();
	}

	@Test
	void disabledWhenManifestMissingMcpSpec() throws IOException {
		Path empty = tempDir.resolve("empty-images.yaml");
		Files.writeString(empty, "images: {}\n", StandardCharsets.UTF_8);
		ContainerSpecReader emptyReader = new ContainerSpecReader(empty);
		MarkitdownContainerMcp provider = new MarkitdownContainerMcp(fakeManager, emptyReader, embeddedUpstream());
		assertThat(provider.isEnabled()).isFalse();
	}

	@Test
	void enabledWhenDockerRuntimeAndMcpSpecPresent() {
		assertThat(provider(fakeManager, embeddedUpstream()).isEnabled()).isTrue();
	}

	@Test
	void disabledProviderIsRegisteredButNotEnabled() {
		// #50 注册/启用分离：未启用容器源仍注册（目录列出、enabled=false）、工具不进全量表
		McpSourceRegistry registry = new McpSourceRegistry(
			java.util.List.of(provider(null, embeddedUpstream())));
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("markitdown");
		assertThat(registry.sources().get(0).enabled()).isFalse();
		assertThat(registry.allToolNames()).isEmpty();
	}

}
