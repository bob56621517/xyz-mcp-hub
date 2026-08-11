package io.xyz.xyz_mcp_hub;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host.playwright.PlaywrightTools;
import io.xyz.xyz_mcp_hub.playwright.PlaywrightProperties;
import io.xyz.xyz_mcp_hub.playwright.WebSessionRegistry;
import io.xyz.xyz_mcp_hub.playwright.internal.SharedChromium;

/**
 * playwright 能力层手工冒烟（非自动测试，#54 自 {@code McpPlaywrightEndpointTest} 迁移）：
 * 起本地页，走通 create session → navigate → snapshot → click → evaluate 主链路，
 * 步骤化输出供 issue 留证。能力层的会话租约语义与工具委托由 {@code PlaywrightToolsTest} 单测
 * （mock 注册表）覆盖；本冒烟验证真实 chromium 下的浏览器操作。
 *
 * <p>运行：{@code ./mvnw exec:java -pl hub -Dexec.mainClass=io.xyz.xyz_mcp_hub.PlaywrightSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-service chromium 需本地安装 playwright chromium 二进制（headless，见 README）
 */
public class PlaywrightSmoke {

	private static final String PAGE_HTML = """
		<!DOCTYPE html>
		<html>
		<head><meta charset="utf-8"><title>Playwright Test Page</title></head>
		<body>
		  <h1>Playwright Test Page</h1>
		  <button id="counter">count: 0</button>
		  <script>
		    const btn = document.getElementById('counter');
		    btn.addEventListener('click', () => {
		      const n = Number(btn.textContent.split(':')[1]) + 1;
		      btn.textContent = 'count: ' + n;
		    });
		  </script>
		</body>
		</html>
		""";

	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> respond(exchange, PAGE_HTML));
		server.start();
		String url = "http://localhost:" + server.getAddress().getPort() + "/";

		SharedChromium sharedChromium = new SharedChromium(new PlaywrightProperties());
		WebSessionRegistry registry = null;
		try {
			System.out.println("[1/6] 依赖检查：playwright chromium 二进制");
			String cdpEndpoint;
			try {
				cdpEndpoint = sharedChromium.cdpEndpoint();
			}
			catch (RuntimeException e) {
				// 未安装 chromium 时 executablePath()/CDP 端口探测抛异常（含 "Executable doesn't exist"）
				System.out.println("      chromium 启动失败（Executable doesn't exist 即未安装）：" + e.getMessage());
				System.out.println("      需先安装 playwright chromium 二进制，退出");
				return;
			}
			System.out.println("      chromium OK（CDP " + cdpEndpoint + "）");

			System.out.println("[2/6] 创建浏览器会话");
			registry = new WebSessionRegistry(sharedChromium, new PlaywrightProperties());
			PlaywrightTools tools = new PlaywrightTools(registry);
			String created = tools.webSession("create", null);
			System.out.println("      " + created);
			String sid = created.substring(created.indexOf("sessionId: ") + "sessionId: ".length());
			System.out.println("[3/6] 导航到本地测试页");
			System.out.println("      " + tools.browserNavigate(sid, url));
			System.out.println("[4/6] 捕获可访问性快照");
			System.out.println("      " + truncate(tools.browserSnapshot(sid), 400));
			System.out.println("[5/6] 点击计数按钮");
			System.out.println("      " + tools.browserClick(sid, "#counter", null, null));
			System.out.println("[6/6] 读取计数结果");
			String count = tools.browserEvaluate(sid, "document.getElementById('counter').textContent");
			System.out.println("      计数结果：" + count);
			boolean ok = count != null && count.contains("count: 1");
			System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）"));
		}
		finally {
			if (registry != null) {
				registry.destroy();
			}
			sharedChromium.destroy();
			server.stop(0);
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

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
