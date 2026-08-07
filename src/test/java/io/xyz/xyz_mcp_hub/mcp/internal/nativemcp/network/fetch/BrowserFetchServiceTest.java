package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.SharedChromium;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.WebSessionRegistry;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.ResolvedTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * fetch 浏览器路径集成测试（issue #19 验收核心）：直接装配服务（主文档 guard 注入放行本地
 * 测试服务的实现，子资源 guard 为真实 {@link SsrUrlGuard}），本地 HttpServer 起 JS 渲染测试页，
 * 验证：JS 渲染动态内容可见、双路径（正文 markdown / raw 完整结构）、浏览器路径下内网
 * iframe/子资源不逃逸、fetch 自身无状态（并发多次抓取不互相污染）、auto 引擎检测脚本升级。
 *
 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless，见 README）
 */
class BrowserFetchServiceTest {

	private static final String JS_PAGE_HTML = """
			<!DOCTYPE html>
			<html>
			<head><meta charset="utf-8"><title>JS 渲染测试文章</title></head>
			<body>
			  <h1>JS 渲染测试文章</h1>
			  <p>这是服务端渲染的引言段落，快路径也能读到。</p>
			  <div id="dynamic"></div>
			  <iframe src="/admin/secret"></iframe>
			  <script>
			    document.addEventListener('DOMContentLoaded', function () {
			      var box = document.getElementById('dynamic');
			      var p = document.createElement('p');
			      p.id = 'js-paragraph';
			      p.textContent = '这段正文由 JavaScript 动态生成，只有浏览器渲染后才能读到。';
			      box.appendChild(p);
			    });
			  </script>
			</body>
			</html>
			""";

	private static final String STATIC_PAGE_HTML = """
			<!DOCTYPE html>
			<html>
			<head><meta charset="utf-8"><title>静态对照页</title></head>
			<body><h1>静态对照页</h1><p>这是无脚本的静态页面，快路径即可。</p></body>
			</html>
			""";

	private HttpServer server;
	private String jsPageUrl;
	private String staticUrl;
	private AtomicInteger adminHits;

	private FetchHttpClient http;
	private SharedChromium sharedChromium;
	private WebSessionRegistry registry;
	private BrowserFetchService browserService;

	@BeforeEach
	void setUp() throws IOException {
		adminHits = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> respond(exchange, JS_PAGE_HTML));
		server.createContext("/static", exchange -> respond(exchange, STATIC_PAGE_HTML));
		server.createContext("/admin/secret", exchange -> {
			adminHits.incrementAndGet();
			respond(exchange, "secret");
		});
		server.start();
		int port = server.getAddress().getPort();
		jsPageUrl = "http://localhost:" + port + "/";
		staticUrl = "http://localhost:" + port + "/static";

		http = new FetchHttpClient();
		sharedChromium = new SharedChromium(true, 30);
		registry = new WebSessionRegistry(sharedChromium, 8, 300, 60);
		browserService = new BrowserFetchService(http, registry, new ReadabilityExtractor(),
				new HtmlToMarkdown(), new PdfTextExtractor(),
				BrowserFetchServiceTest::allowLocalhost, new SsrUrlGuard(), 1000);
	}

	@AfterEach
	void tearDown() {
		registry.destroy();
		sharedChromium.destroy();
		http.close();
		server.stop(0);
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 放行本地测试服务的主文档 guard：返回回环地址供锁定 IP 直连。 */
	private static ResolvedTarget allowLocalhost(String url) {
		URI uri = URI.create(url);
		int port = uri.getPort() > 0 ? uri.getPort()
			: ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
		return new ResolvedTarget(uri, uri.getHost(), port, List.of(InetAddress.getLoopbackAddress()));
	}

	// ---- 验收：engine=browser 抓取本地 JS 渲染测试页，动态内容可见、正文 markdown 合理 ----

	@Test
	void browserPathRendersJsDynamicContent() {
		String out = browserService.fetch(jsPageUrl, null, null, false, false);
		assertThat(out)
			.contains("服务端渲染")
			.contains("由 JavaScript 动态生成")
			.doesNotContain("<script");
	}

	// ---- 验收：双路径——正文（readability 清洗）与 raw（完整结构）分别可用 ----

	@Test
	void browserPathRawReturnsFullStructure() {
		String out = browserService.fetch(jsPageUrl, null, null, true, false);
		assertThat(out)
			.contains("<html")
			.contains("<iframe")
			.contains("<script");
	}

	// ---- 验收：浏览器路径下 SSRF 拦截生效（页面含内网 iframe/子资源不逃逸） ----

	@Test
	void browserPathBlocksIntranetSubresource() {
		browserService.fetch(jsPageUrl, null, null, false, false);
		assertThat(adminHits.get()).as("内网 iframe 子资源不应逃逸（/admin/secret 不应被请求）")
			.isZero();
	}

	// ---- 截图支持 ----

	@Test
	void browserPathScreenshotReturnsPngDataUrl() {
		String out = browserService.fetch(jsPageUrl, null, null, false, true);
		assertThat(out).contains("data:image/png;base64,");
		String base64 = out.substring(out.indexOf("data:image/png;base64,")
				+ "data:image/png;base64,".length()).strip();
		byte[] image = java.util.Base64.getDecoder().decode(base64);
		assertThat(image).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
		assertThat(image.length).isGreaterThan(1000);
	}

	// ---- 分块在浏览器路径同样生效 ----

	@Test
	void browserPathAppliesChunking() {
		String out = browserService.fetch(jsPageUrl, 40, 0, false, false);
		assertThat(out).contains("omitted");
		assertThat(out.length()).isLessThan(200);
	}

	// ---- 验收：fetch 自身无状态——并发多次抓取不互相污染 ----

	@Test
	void concurrentFetchesAreStateless() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<String> js = pool.submit((Callable<String>) () ->
					browserService.fetch(jsPageUrl, null, null, false, false));
			Future<String> staticFuture = pool.submit((Callable<String>) () ->
					browserService.fetch(staticUrl, null, null, false, false));
			String jsOut = js.get(90, TimeUnit.SECONDS);
			String staticOut = staticFuture.get(90, TimeUnit.SECONDS);
			// JS 页：读到动态渲染内容；静态页：读不到（各自独立 context，不互相污染）
			assertThat(jsOut).contains("由 JavaScript 动态生成");
			assertThat(staticOut).contains("静态对照页").doesNotContain("动态生成");
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- auto 引擎：快路径检测到脚本后升级浏览器路径 ----

	@Test
	void autoEngineUpgradesToBrowserWhenScriptDetected() {
		FetchService fastService = new FetchService(
				BrowserFetchServiceTest::allowLocalhost, http, new HtmlToMarkdown(), new PdfTextExtractor());
		FetchTools tools = new FetchTools(fastService, browserService);
		String out = tools.fetch(jsPageUrl, null, null, null, "auto", null);
		// JS 页含 script → auto 升级浏览器路径 → 读到动态渲染内容
		assertThat(out).contains("由 JavaScript 动态生成");
	}

	@Test
	void curlEngineStaysOnFastPath() {
		FetchService fastService = new FetchService(
				BrowserFetchServiceTest::allowLocalhost, http, new HtmlToMarkdown(), new PdfTextExtractor());
		FetchTools tools = new FetchTools(fastService, browserService);
		String out = tools.fetch(jsPageUrl, null, null, null, "curl", null);
		// 快路径不渲染 JS，只能读到服务端 HTML 中的静态引言
		assertThat(out)
			.contains("服务端渲染")
			.doesNotContain("由 JavaScript 动态生成");
	}
}
