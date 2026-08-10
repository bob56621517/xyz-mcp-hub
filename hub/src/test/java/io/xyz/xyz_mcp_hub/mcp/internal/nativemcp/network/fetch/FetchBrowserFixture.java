package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.ResolvedTarget;

/**
 * fetch 浏览器路径测试共享脚手架：本地 JS 渲染测试页 HTML、HTTP 响应 helper 与
 * 「放行本地主文档」的 guard 实现（BrowserFetchServiceTest 与 FetchRealApiSmoke 共用）。
 */
public final class FetchBrowserFixture {

	/** 含内联 JS 动态渲染 + 指向内网 iframe 的测试页（快路径读不到动态内容，浏览器渲染后可见）。 */
	public static final String JS_PAGE_HTML = """
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

	private FetchBrowserFixture() {
	}

	public static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 放行本地测试服务的主文档 guard：返回回环地址供锁定 IP 直连（SSRF 拦截本身由真实 guard 用例覆盖）。 */
	public static ResolvedTarget allowLocalhost(String url) {
		URI uri = URI.create(url);
		int port = uri.getPort() > 0 ? uri.getPort()
			: ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
		return new ResolvedTarget(uri, uri.getHost(), port, List.of(InetAddress.getLoopbackAddress()));
	}
}
