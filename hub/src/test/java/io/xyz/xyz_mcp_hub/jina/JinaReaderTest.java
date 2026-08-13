package io.xyz.xyz_mcp_hub.jina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jina 顶级模块能力测试（ADR-0016，S1 能力层）：{@link JinaReader} 的 http(s) 代抓（POST JSON）与
 * file:// 本地文件上传（multipart）。端点来自配置（测试注入内嵌模拟上游），不依赖 docker/容器。
 * 非冒烟、不触网、无外部服务。
 */
class JinaReaderTest {

	private static final AtomicReference<String> LAST_CONTENT_TYPE = new AtomicReference<>();
	private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

	private static HttpServer upstream;
	private static int upstreamPort;

	@TempDir
	static Path tempDir;

	@BeforeAll
	static void startUpstream() throws IOException {
		upstream = HttpServer.create(new InetSocketAddress(0), 0);
		upstream.createContext("/", JinaReaderTest::respondMarkdown);
		upstream.start();
		upstreamPort = upstream.getAddress().getPort();
	}

	/** 内嵌模拟 jina reader 上游：记录 Content-Type 与请求体，返回固定 markdown。 */
	private static void respondMarkdown(HttpExchange exchange) throws IOException {
		LAST_CONTENT_TYPE.set(exchange.getRequestHeaders().getFirst("Content-Type"));
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
	void reset() {
		LAST_CONTENT_TYPE.set(null);
		LAST_BODY.set(null);
	}

	/** 指向内嵌模拟上游的配置化 reader。 */
	private JinaReader reader() {
		return new JinaReader("http://localhost:" + upstreamPort);
	}

	// ---- 验收：http(s) 代抓（POST JSON） ----

	@Test
	void readUrlPostsJsonAndReturnsMarkdown() {
		String result = reader().readUrl("https://example.com/page");
		assertThat(result).contains("mock markdown");
		assertThat(LAST_BODY.get()).contains("https://example.com/page");
	}

	// ---- 验收：file:// 本地文件上传（multipart） ----

	@Test
	void readLocalFileUploadsMultipartAndReturnsMarkdown() throws IOException {
		Path file = tempDir.resolve("doc.pdf");
		Files.write(file, new byte[] { '%', 'P', 'D', 'F', '-' });
		String result = reader().readLocalFile(file.toUri().toString());
		assertThat(result).contains("mock markdown");
		assertThat(LAST_CONTENT_TYPE.get()).startsWith("multipart/form-data");
		assertThat(LAST_BODY.get()).contains("name=\"file\"");
		assertThat(LAST_BODY.get()).contains("doc.pdf");
	}

	@Test
	void readLocalFileRejectsRemoteHostPath() {
		assertThatThrownBy(() -> reader().readLocalFile("file://remote-host/etc/passwd"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("仅支持本地 file:// 路径");
	}

	@Test
	void readLocalFileRejectsMissingFile() {
		assertThatThrownBy(() -> reader().readLocalFile("file:///no/such/file.md"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("读取本地文件失败");
	}

	// ---- 验收：isAvailable 优雅降级（jina.url 配置门控） ----

	@Test
	void availableWhenUrlConfigured() {
		assertThat(reader().isAvailable()).isTrue();
	}

	@Test
	void unavailableWithoutUrl() {
		assertThat(new JinaReader("").isAvailable()).isFalse();
		assertThat(new JinaReader("  ").isAvailable()).isFalse();
		assertThat(new JinaReader(null).isAvailable()).isFalse();
	}

	@Test
	void callFailsFastWithoutConfiguredEndpoint() {
		JinaReader blank = new JinaReader("");
		assertThatThrownBy(() -> blank.readUrl("https://example.com"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jina 端点未配置");
	}

}
