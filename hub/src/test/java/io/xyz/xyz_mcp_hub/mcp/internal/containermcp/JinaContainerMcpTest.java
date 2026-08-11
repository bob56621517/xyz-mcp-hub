package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.mcp.internal.single.McpSourceRegistry;
import io.xyz.xyz_mcp_hub.mcp.internal.single.ToolFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

/**
 * ContainerMcp jina 源单测（#38，次 seam A）：注入 fake {@link ContainerManager} 与指向内嵌模拟 REST
 * 上游的 {@link ContainerEndpoint}，不启真实 docker、不启 hub Spring 上下文。
 *
 * <p>覆盖 #38 验收：includes 源展开暴露 {@code jina_reader}（静态冒烟清单）、工具调用 POST
 * {@code {"url":...}} 到容器 REST 端点（内嵌上游记录收到的 body）、SSRF 预检拦截内网地址、非 http(s)
 * scheme 白名单拦截、容器启动失败/上游不可达时源降级返回友好文本、docker 运行时缺失/清单缺失时
 * isEnabled=false、container 源目录元数据 protocol=rest。</p>
 */
class JinaContainerMcpTest {

	private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

	private static HttpServer upstream;
	private static int upstreamPort;

	@TempDir
	static Path tempDir;

	private ContainerSpec spec;
	private ContainerSpecReader specReader;
	private ContainerManager fakeManager;

	@BeforeAll
	static void startUpstream() throws IOException {
		upstream = HttpServer.create(new InetSocketAddress(0), 0);
		upstream.createContext("/", exchange -> respondMarkdown(exchange));
		upstream.start();
		upstreamPort = upstream.getAddress().getPort();
	}

	/** 内嵌模拟 jina reader REST 上游：记录收到的 POST body，返回固定 markdown。 */
	private static void respondMarkdown(HttpExchange exchange) throws IOException {
		LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] bytes = "# mock markdown\n\njina 返回正文".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@AfterAll
	static void stopUpstream() {
		if (upstream != null) {
			upstream.stop(0);
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		Path manifest = tempDir.resolve("mcp-images.yaml");
		Files.writeString(manifest, """
			images:
			  jina:
			    image: ghcr.io/jina-ai/reader:latest
			    protocol: rest
			    port: 8081
			    hostPort: 18081
			""", StandardCharsets.UTF_8);
		specReader = new ContainerSpecReader(manifest);
		spec = specReader.byName("jina").orElseThrow();
		fakeManager = mock(ContainerManager.class);
		when(fakeManager.ensureRunning(any(ContainerSpec.class)))
			.thenReturn(new ContainerHandle(spec, "cid-fake"));
		LAST_BODY.set(null);
	}

	/** 指向内嵌模拟 REST 上游的端点（测试注入，替代默认 127.0.0.1:hostPort）。 */
	private ContainerEndpoint embeddedUpstream() {
		return new ContainerEndpoint() {
			@Override
			public String mcpUrl(ContainerSpec s) {
				return "http://localhost:" + upstreamPort + "/mcp";
			}

			@Override
			public String restUrl(ContainerSpec s) {
				return "http://localhost:" + upstreamPort + "/";
			}
		};
	}

	private JinaContainerMcp provider(ContainerManager manager, ContainerEndpoint endpoint) {
		return new JinaContainerMcp(manager, specReader, endpoint);
	}

	private ToolCallback readerTool() {
		return provider(fakeManager, embeddedUpstream()).getTools().get(0);
	}

	// ---- 验收 1：includes=jina 暴露 jina_reader（静态冒烟清单） ----

	@Test
	void sourceExposesPrefixedReaderToolInRegistry() {
		McpSourceRegistry registry = new McpSourceRegistry(
			List.of(provider(fakeManager, embeddedUpstream())));
		assertThat(registry.allToolNames()).containsExactly("jina_reader");
	}

	@Test
	void includesSourceNameExpandsToReaderTool() {
		McpSourceRegistry registry = new McpSourceRegistry(
			List.of(provider(fakeManager, embeddedUpstream())));
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[jina]"), Optional.empty())))
			.containsExactly("jina_reader");
	}

	// ---- 验收 2：工具调用 POST 转发到容器 REST 端点（首用拉起） ----

	@Test
	void toolCallForwardsUrlToContainerAndReturnsMarkdown() {
		// @Tool 返回 String 会被 Spring AI 结果转换器 JSON 编码（全库既有行为），用 contains 断言
		String result = readerTool().call("{\"url\":\"https://example.com/page\"}");
		assertThat(result).contains("mock markdown");
		assertThat(LAST_BODY.get()).contains("https://example.com/page");
		// 首用拉起：ensureRunning 被调用
		verify(fakeManager, atLeastOnce()).ensureRunning(spec);
	}

	// ---- 验收：SSRF 预检（http(s) url 交给容器前拦截内网/保留段） ----

	@Test
	void ssrfRejectsPrivateUrlBeforeForwarding() {
		String result = readerTool().call("{\"url\":\"http://127.0.0.1:8080/internal\"}");
		assertThat(result).contains("SSRF 防护拦截");
		// 内网地址被拒：不转发到容器
		assertThat(LAST_BODY.get()).isNull();
		verify(fakeManager, never()).ensureRunning(any(ContainerSpec.class));
	}

	@Test
	void nonHttpSchemeRejectedByWhitelist() {
		String result = readerTool().call("{\"url\":\"file:///etc/hosts\"}");
		assertThat(result).contains("SSRF 防护拦截");
		assertThat(LAST_BODY.get()).isNull();
	}

	@Test
	void blankUrlReturnsHint() {
		String result = readerTool().call("{\"url\":\"\"}");
		assertThat(result).contains("需要 url 参数");
		assertThat(LAST_BODY.get()).isNull();
	}

	// ---- 验收：容器启动失败 / 上游不可达 → 源降级、返回友好文本 ----

	@Test
	void containerStartFailureReturnsFriendlyError() {
		when(fakeManager.ensureRunning(any(ContainerSpec.class)))
			.thenThrow(new IllegalStateException("docker daemon 不可用"));
		String result = readerTool().call("{\"url\":\"https://example.com/x\"}");
		assertThat(result).contains("jina 容器不可用").contains("docker daemon 不可用");
	}

	@Test
	void upstreamUnreachableReturnsFriendlyError() {
		ContainerEndpoint unreachable = new ContainerEndpoint() {
			@Override
			public String mcpUrl(ContainerSpec s) {
				return "http://localhost:1/mcp";
			}

			@Override
			public String restUrl(ContainerSpec s) {
				return "http://localhost:1/";
			}
		};
		ToolCallback tool = provider(fakeManager, unreachable).getTools().get(0);
		String result = tool.call("{\"url\":\"https://example.com/x\"}");
		assertThat(result).contains("jina 容器不可用");
	}

	// ---- 验收：无 docker 运行时 / 清单缺失 → isEnabled=false 降级 ----

	@Test
	void disabledWhenContainerRuntimeAbsent() {
		assertThat(provider(null, embeddedUpstream()).isEnabled()).isFalse();
	}

	@Test
	void disabledWhenManifestMissingRestSpec() throws IOException {
		Path empty = tempDir.resolve("empty-images.yaml");
		Files.writeString(empty, "images: {}\n", StandardCharsets.UTF_8);
		ContainerSpecReader emptyReader = new ContainerSpecReader(empty);
		JinaContainerMcp provider = new JinaContainerMcp(fakeManager, emptyReader, embeddedUpstream());
		assertThat(provider.isEnabled()).isFalse();
	}

	@Test
	void enabledWhenDockerRuntimeAndRestSpecPresent() {
		assertThat(provider(fakeManager, embeddedUpstream()).isEnabled()).isTrue();
	}

	@Test
	void disabledProviderIsRegisteredButNotEnabled() {
		// #50 注册/启用分离：未启用容器源仍注册（目录列出、enabled=false）、工具不进全量表
		McpSourceRegistry registry = new McpSourceRegistry(
			List.of(provider(null, embeddedUpstream())));
		assertThat(registry.sources()).extracting(McpSourceRegistry.McpSource::name).containsExactly("jina");
		assertThat(registry.sources().get(0).enabled()).isFalse();
		assertThat(registry.allToolNames()).isEmpty();
	}

	// ---- 验收：容器源目录元数据 protocol=rest（#38 修复 toSource 恒 null） ----

	@Test
	void containerSourceCarriesRestProtocolMetadata() {
		McpSourceRegistry registry = new McpSourceRegistry(
			List.of(provider(fakeManager, embeddedUpstream())));
		McpSourceRegistry.McpSource source = registry.sources().get(0);
		assertThat(source.type()).isEqualTo(SourceType.CONTAINER);
		assertThat(source.protocol()).isEqualTo(Protocol.REST);
	}

}
