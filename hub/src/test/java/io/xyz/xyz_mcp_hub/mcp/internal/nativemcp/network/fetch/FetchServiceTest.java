package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.ResolvedTarget;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.SsrGuardException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FetchService} 测试：本地 HttpServer + 放行 {@link FetchUrlGuard} 验证抓取管线
 * （markdown/raw/分块/重定向/PDF/桩），真实 {@link SsrUrlGuard} 验证 SSRF 拦截集成。
 * 无外部网络依赖（PDF 由 PDFBox 内存生成）。
 */
class FetchServiceTest {

	private static final String PAGE_HTML = """
		<!DOCTYPE html>
		<html>
		<head><meta charset="utf-8"><title>Fetch Test Page</title></head>
		<body>
		  <h1>Fetch Test Page</h1>
		  <p>Hello from <a href="https://example.com">example</a>.</p>
		</body>
		</html>
		""";

	/** 200 字符可预测文本，供分块断言。 */
	private static final String LONG_TEXT = "0123456789".repeat(20);

	private static final String PLAIN_TEXT = "plain text body";

	private HttpServer server;
	private String baseUrl;
	private FetchService service;
	private FetchHttpClient http;

	@BeforeEach
	void startServerAndService() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/page", exchange -> respond(exchange, 200, "text/html; charset=utf-8",
				PAGE_HTML.getBytes(StandardCharsets.UTF_8)));
		server.createContext("/redirect", exchange -> respondRedirect(exchange, "/page"));
		server.createContext("/loop", exchange -> respondRedirect(exchange, "/loop"));
		server.createContext("/start", exchange -> respondRedirect(exchange, "/evil"));
		server.createContext("/evil", exchange -> respond(exchange, 200, "text/html; charset=utf-8",
				PAGE_HTML.getBytes(StandardCharsets.UTF_8)));
		server.createContext("/notfound", exchange -> respond(exchange, 404, "text/html; charset=utf-8",
				"<h1>Not Found</h1>".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/pdf", exchange -> respond(exchange, 200, "application/pdf", samplePdf()));
		server.createContext("/img", exchange -> respond(exchange, 200, "image/png", new byte[] { 0x01, 0x02 }));
		server.createContext("/long", exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
				LONG_TEXT.getBytes(StandardCharsets.UTF_8)));
		server.createContext("/text", exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
				PLAIN_TEXT.getBytes(StandardCharsets.UTF_8)));
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

		http = new FetchHttpClient();
		service = new FetchService(allowLocal(), http, new HtmlToMarkdown(), new PdfTextExtractor());
	}

	@AfterEach
	void tearDown() {
		http.close();
		server.stop(0);
	}

	// ---- 快路径抓取管线（放行 checker，连本地服务） ----

	@Test
	void fetchesHtmlAsMarkdown() {
		String md = service.fetch(baseUrl + "/page", null, null, null);
		assertThat(md).contains("# Fetch Test Page");
		assertThat(md).contains("Hello from [example](https://example.com).");
	}

	@Test
	void rawReturnsOriginalBody() {
		String raw = service.fetch(baseUrl + "/page", null, null, true);
		assertThat(raw).contains("<h1>Fetch Test Page</h1>");
		assertThat(raw).doesNotContain("# Fetch Test Page");
	}

	@Test
	void chunkingAppliesStartIndexAndMaxLength() {
		String out = service.fetch(baseUrl + "/long", 100, 50, null);
		assertThat(out).startsWith("(Content before the start_index is omitted)");
		assertThat(out).endsWith("(Content after the end_index is omitted)");
		assertThat(out).contains(LONG_TEXT.substring(50, 150));
		assertThat(out.replaceAll("\\(Content .*?\\)", "").trim().length()).isEqualTo(100);
	}

	@Test
	void plainTextReturnedAsIs() {
		assertThat(service.fetch(baseUrl + "/text", null, null, null)).isEqualTo(PLAIN_TEXT);
	}

	@Test
	void redirectFollowedHopByHop() {
		String md = service.fetch(baseUrl + "/redirect", null, null, null);
		assertThat(md).contains("# Fetch Test Page");
	}

	@Test
	void redirectLoopAborted() {
		assertThatThrownBy(() -> service.fetch(baseUrl + "/loop", null, null, null))
			.isInstanceOf(FetchException.class)
			.hasMessageContaining("重定向次数超过上限");
	}

	@Test
	void pdfUrlExtractedToText() {
		String text = service.fetch(baseUrl + "/pdf", null, null, null);
		assertThat(text).contains("Hello PDF from fetch pipeline");
	}

	@Test
	void unsupportedMediaTypeStubbed() {
		String out = service.fetch(baseUrl + "/img", null, null, null);
		assertThat(out).contains("该内容类型暂不支持").contains("image/png");
	}

	@Test
	void blankUrlReturnsHint() {
		assertThat(service.fetch("  ", null, null, null)).contains("请提供要抓取的 URL");
	}

	@Test
	void httpErrorStatusThrows() {
		assertThatThrownBy(() -> service.fetch(baseUrl + "/notfound", null, null, null))
			.isInstanceOf(FetchException.class)
			.hasMessageContaining("HTTP 404");
	}

	@Test
	void redirectChainGuardRejectsNextHop() {
		// 第一跳放行本地，第二跳（/evil）被 guard 拒绝 → 验证逐跳重定向重新校验
		FetchUrlGuard chainGuard = url -> {
			if (url.contains("/evil")) {
				throw new SsrGuardException("目标 IP 落在内网/保留段，已拦截：" + url);
			}
			return allowLocal().resolveAndCheck(url);
		};
		FetchService guarded = new FetchService(chainGuard, http, new HtmlToMarkdown(), new PdfTextExtractor());
		assertThatThrownBy(() -> guarded.fetch(baseUrl + "/start", null, null, null))
			.isInstanceOf(SsrGuardException.class);
	}

	// ---- SSRF 集成（真实 guard） ----

	@Test
	void realGuardRejectsLoopbackLiteral() {
		FetchService guarded = new FetchService(new SsrUrlGuard()::resolveAndCheck, http,
				new HtmlToMarkdown(), new PdfTextExtractor());
		assertThatThrownBy(() -> guarded.fetch(baseUrl + "/page", null, null, null))
			.isInstanceOf(SsrGuardException.class);
	}

	@Test
	void realGuardRejectsDomainResolvedToPrivateIp() {
		SsrUrlGuard guard = new SsrUrlGuard(host -> new InetAddress[] { ip("10.0.0.5") }, Duration.ofSeconds(5));
		FetchService guarded = new FetchService(guard::resolveAndCheck, http,
				new HtmlToMarkdown(), new PdfTextExtractor());
		assertThatThrownBy(() -> guarded.fetch("http://internal.test/page", null, null, null))
			.isInstanceOf(SsrGuardException.class);
	}

	// ---- 工具 ----

	/** 放行 127.0.0.1 本地服务的 checker：锁定 host 的 IP 供建连，重定向时继续校验。 */
	private static FetchUrlGuard allowLocal() {
		return url -> {
			URI uri = URI.create(url);
			String host = uri.getHost();
			int port = uri.getPort() > 0 ? uri.getPort()
				: ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
			return new ResolvedTarget(uri, host, port, List.of(ip(host)));
		};
	}

	private static InetAddress ip(String literal) {
		try {
			return InetAddress.getByName(literal);
		}
		catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static void respondRedirect(HttpExchange exchange, String location) throws IOException {
		exchange.getResponseHeaders().set("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	/** 用 PDFBox 在内存生成含一行文本的最小 PDF。 */
	private static byte[] samplePdf() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(PDType1Font.HELVETICA, 12);
				cs.newLineAtOffset(50, 700);
				cs.showText("Hello PDF from fetch pipeline");
				cs.endText();
			}
			doc.save(out);
		}
		catch (IOException e) {
			throw new IllegalStateException("内存生成 PDF 失败", e);
		}
		return out.toByteArray();
	}
}
