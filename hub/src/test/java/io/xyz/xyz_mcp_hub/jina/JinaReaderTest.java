package io.xyz.xyz_mcp_hub.jina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xyz.xyz_mcp_hub.docker.ContainerEndpoint;
import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jina 顶级模块能力测试（#53，S1 能力层）：{@link JinaReader} 的容器代抓（fake {@link ContainerManager}
 * + 内嵌模拟 REST 上游）与 file:// 本地文件解析（非冒烟、不触网、不依赖容器）。
 */
class JinaReaderTest {

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

	// ---- 验收：http(s) 容器代抓（首用拉起） ----

	@Test
	void readUrlForwardsToContainerAndReturnsMarkdown() {
		JinaReader reader = new JinaReader(fakeManager, specReader, embeddedUpstream());
		String result = reader.readUrl("https://example.com/page");
		assertThat(result).contains("mock markdown");
		assertThat(LAST_BODY.get()).contains("https://example.com/page");
		verify(fakeManager, atLeastOnce()).ensureRunning(spec);
	}

	// ---- 验收：file:// 本地文件解析（非冒烟、不触网、不依赖容器） ----

	@Test
	void readLocalFileReturnsLocalContent() throws IOException {
		Path file = tempDir.resolve("doc.md");
		Files.writeString(file, "# 本地文档\n\n正文内容", StandardCharsets.UTF_8);
		JinaReader reader = new JinaReader(fakeManager, specReader, embeddedUpstream());
		assertThat(reader.readLocalFile(file.toUri().toString()))
			.isEqualTo("# 本地文档\n\n正文内容");
	}

	@Test
	void readLocalFileRejectsRemoteHostPath() {
		JinaReader reader = new JinaReader(fakeManager, specReader, embeddedUpstream());
		assertThatThrownBy(() -> reader.readLocalFile("file://remote-host/etc/passwd"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("仅支持本地 file:// 路径");
	}

	@Test
	void readLocalFileRejectsMissingFile() {
		JinaReader reader = new JinaReader(fakeManager, specReader, embeddedUpstream());
		assertThatThrownBy(() -> reader.readLocalFile("file:///no/such/file.md"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("读取本地文件失败");
	}

	// ---- 验收：isAvailable 优雅降级 ----

	@Test
	void unavailableWithoutDockerRuntime() {
		JinaReader reader = new JinaReader(null, specReader, embeddedUpstream());
		assertThat(reader.isAvailable()).isFalse();
	}

	@Test
	void unavailableWithoutRestSpec() throws IOException {
		Path empty = tempDir.resolve("empty-images.yaml");
		Files.writeString(empty, "images: {}\n", StandardCharsets.UTF_8);
		ContainerSpecReader emptyReader = new ContainerSpecReader(empty);
		JinaReader reader = new JinaReader(fakeManager, emptyReader, embeddedUpstream());
		assertThat(reader.isAvailable()).isFalse();
	}

	@Test
	void availableWithDockerRuntimeAndRestSpec() {
		JinaReader reader = new JinaReader(fakeManager, specReader, embeddedUpstream());
		assertThat(reader.isAvailable()).isTrue();
	}

}
