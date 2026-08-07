package io.xyz.xyz_mcp_hub;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.BrowserFetchService;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchHttpClient;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchService;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.HtmlToMarkdown;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.PdfTextExtractor;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.ReadabilityExtractor;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.SharedChromium;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright.WebSessionRegistry;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.ResolvedTarget;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.SsrGuardException;

/**
 * Fetch 端点真实抓取冒烟（手工运行，非自动测试）。
 *
 * <p>验证 {@code /mcp/builtin/fetch} 快路径（真实公网：HTML→markdown、raw、分块、SSRF 拦截、
 * PDF 管线）与浏览器路径（engine=browser：本地 JS 渲染页动态内容、raw 完整结构、内网子资源
 * 不逃逸、截图）。步骤化 stdout 输出供 issue 留证。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.FetchRealApiSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web 需真实外部网络（example.com、www.w3.org）
 * @requires-service chromium 浏览器路径需本地安装 playwright chromium 二进制（headless）
 */
public class FetchRealApiSmoke {

	private static final String EXAMPLE_URL = "https://example.com/";
	private static final String PDF_URL =
		"https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";

	private static final String JS_PAGE_HTML = """
			<!DOCTYPE html>
			<html>
			<head><meta charset="utf-8"><title>JS 渲染冒烟页</title></head>
			<body>
			  <h1>JS 渲染冒烟页</h1>
			  <p>这是服务端渲染的引言段落。</p>
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

	public static void main(String[] args) throws IOException {
		FetchHttpClient http = new FetchHttpClient();
		HttpServer localServer = null;
		SharedChromium chromium = null;
		WebSessionRegistry registry = null;
		try {
			HtmlToMarkdown htmlToMarkdown = new HtmlToMarkdown();
			PdfTextExtractor pdfTextExtractor = new PdfTextExtractor();
			FetchService fastService = new FetchService(http, htmlToMarkdown, pdfTextExtractor);

			// ---- 公网快路径步骤：代理 fake-ip 环境（域名解析到保留段被 SSRF 正确拦截）时整体跳过 ----
			boolean publicWebOk = false;
			String md = "";
			String raw = "";
			String blocked = "";
			String pdfText = "";
			try {
				System.out.println("[1/5] 快路径抓取 HTML 并转 Markdown：" + EXAMPLE_URL);
				md = fastService.fetch(EXAMPLE_URL, 2000, 0, false);
				System.out.println("      结果：\n" + truncate(md, 400));

				System.out.println("[2/5] 快路径 raw=true 返回原始内容");
				raw = fastService.fetch(EXAMPLE_URL, 500, 0, true);
				System.out.println("      是否含 HTML 标记：" + raw.toLowerCase().contains("<html"));

				System.out.println("[3/5] 快路径分块读取：max_length=200, start_index=100");
				String chunked = fastService.fetch(EXAMPLE_URL, 200, 100, false);
				System.out.println("      结果：\n" + truncate(chunked, 260));

				System.out.println("[4/5] 快路径 SSRF 拦截：http://127.0.0.1:1/");
				try {
					fastService.fetch("http://127.0.0.1:1/", null, null, null);
					blocked = "未拦截！";
				}
				catch (SsrGuardException e) {
					blocked = "SSRF 防护拦截：" + e.getMessage();
				}
				System.out.println("      结果：" + blocked);

				System.out.println("[5/5] 快路径 PDF 管线：" + PDF_URL);
				pdfText = fastService.fetch(PDF_URL, 2000, 0, false);
				System.out.println("      结果：\n" + truncate(pdfText, 400));
				publicWebOk = true;
			}
			catch (SsrGuardException e) {
				// 本机走代理 fake-ip（Clash 类），公网域名解析到保留段被 SSRF 防护正确拦截 → 环境不可达，公网步骤跳过
				System.out.println("[环境] 公网域名被解析到代理 fake-ip 保留段，SSRF 防护正确拦截，本机公网不可达，公网步骤跳过。拦截详情：" + e.getMessage());
			}

			// ---- 浏览器路径：本地 JS 渲染页（放行本地主文档 guard + 真实子资源 guard，必测） ----
			localServer = HttpServer.create(new InetSocketAddress(0), 0);
			AtomicInteger adminHits = new AtomicInteger();
			localServer.createContext("/", exchange -> respond(exchange, JS_PAGE_HTML));
			localServer.createContext("/admin/secret", exchange -> {
				adminHits.incrementAndGet();
				respond(exchange, "secret");
			});
			localServer.start();
			String jsUrl = "http://localhost:" + localServer.getAddress().getPort() + "/";

			chromium = new SharedChromium(true, 30);
			registry = new WebSessionRegistry(chromium, 8, 300, 60);
			BrowserFetchService browserService = new BrowserFetchService(http, registry,
					new ReadabilityExtractor(), htmlToMarkdown, pdfTextExtractor,
					FetchRealApiSmoke::allowLocalhost, new SsrUrlGuard(), 1000);

			System.out.println("[6/9] 浏览器路径 engine=browser 抓取本地 JS 渲染页");
			String jsMd = browserService.fetch(jsUrl, 2000, 0, false, false);
			System.out.println("      结果：\n" + truncate(jsMd, 500));
			boolean jsRendered = jsMd.contains("由 JavaScript 动态生成");

			System.out.println("[7/9] 浏览器路径 raw=true 返回完整结构");
			String jsRaw = browserService.fetch(jsUrl, 2000, 0, true, false);
			System.out.println("      是否含 iframe：" + jsRaw.contains("<iframe")
				+ "，是否含 script：" + jsRaw.contains("<script"));

			System.out.println("[8/9] 浏览器路径 SSRF：页面内 iframe 指向内网不逃逸");
			browserService.fetch(jsUrl, 500, 0, false, false);
			System.out.println("      内网 iframe /admin/secret 实际请求数：" + adminHits.get());
			boolean subresourceBlocked = adminHits.get() == 0;

			System.out.println("[9/9] 浏览器路径截图");
			String shot = browserService.fetch(jsUrl, 500, 0, false, true);
			System.out.println("      是否含 PNG 截图：" + shot.contains("data:image/png;base64,"));

			boolean browserPathOk = jsRendered
					&& jsRaw.contains("<iframe")
					&& subresourceBlocked
					&& shot.contains("data:image/png;base64,");
			boolean publicOk = !publicWebOk || (md.contains("Example Domain")
					&& raw.toLowerCase().contains("<html")
					&& blocked.contains("SSRF 防护拦截")
					&& !pdfText.contains("暂不支持")
					&& pdfText.strip().length() > 0);
			String note = publicWebOk ? "" : "（公网步骤因代理 fake-ip 环境跳过，SSRF 防护正确拦截）";
			boolean ok = browserPathOk && publicOk;
			System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）") + note);
		}
		finally {
			if (registry != null) {
				registry.destroy();
			}
			if (chromium != null) {
				chromium.destroy();
			}
			if (localServer != null) {
				localServer.stop(0);
			}
			http.close();
		}
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

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
