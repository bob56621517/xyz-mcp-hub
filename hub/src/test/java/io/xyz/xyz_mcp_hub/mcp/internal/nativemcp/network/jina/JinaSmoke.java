package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.jina;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.xyz.xyz_mcp_hub.jina.JinaReader;
import org.springframework.ai.tool.ToolCallback;

/**
 * 真实 jina 冒烟（手工运行，非自动测试）——ADR-0016 验收：jina 引擎经 compose 部署（127.0.0.1:18081），
 * hub 侧 {@code jina_reader} 工具真实代抓（网页→markdown）+ SSRF 拦截 + file:// 本地文件上传转换。
 * **不做容器编排**——引擎生命周期由 compose 承担，本冒烟只验证 hub 侧消费端点。
 *
 * <p>运行：{@code ./mvnw exec:java -pl hub -Dexec.mainClass=io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.jina.JinaSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}
 * （在仓库根目录执行；可用参数 1 覆盖端点 baseUrl）</p>
 *
 * @requires-engine jina 容器需先 {@code docker compose up -d}（compose.yml 起 jina，暴露 127.0.0.1:18081）
 * @requires-web 真实公网代抓（example.com）
 */
public class JinaSmoke {

	private static final String EXAMPLE_URL = "https://example.com";
	private static final String PRIVATE_URL = "http://127.0.0.1:8080/internal";

	/** 就绪探测单次请求超时。 */
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

	public static void main(String[] args) throws IOException {
		String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:18081";

		System.out.println("[1/4] 引擎就绪检查：probe " + baseUrl);
		if (!probeReady(baseUrl)) {
			System.out.println("      jina 引擎未就绪（请先 docker compose up -d），退出");
			return;
		}
		System.out.println("      引擎就绪");

		JinaReader jinaReader = new JinaReader(baseUrl);
		JinaTools jinaTools = new JinaTools(jinaReader);
		System.out.println("      jina 源 enabled=" + jinaTools.isEnabled());
		ToolCallback tool = jinaTools.getTools().get(0);

		System.out.println("[2/4] 真实代抓 https://example.com → markdown");
		String md = tool.call("{\"url\":\"" + EXAMPLE_URL + "\"}");
		System.out.println("      返回 Markdown（截断 400 字符）:\n------\n" + truncate(md, 400) + "\n------");
		// @Tool 返回 String 会被 JSON 编码（全库既有行为），用 contains 判定
		boolean converted = md != null && md.contains("Example Domain");
		System.out.println("      判定：" + (converted ? "通过（真实代抓返回 markdown）" : "失败"));

		System.out.println("[3/4] 验收：SSRF 预检拦截内网地址（http(s) url 交给引擎前）");
		String guarded = tool.call("{\"url\":\"" + PRIVATE_URL + "\"}");
		System.out.println("      结果: " + guarded);
		boolean ssrf = guarded.contains("SSRF 防护拦截");
		System.out.println("      判定：" + (ssrf ? "通过（内网地址被拒）" : "失败"));

		System.out.println("[4/4] 验收：file:// 本地文件上传转换（hub 宿主文件 → multipart 上传）");
		Path localFile = Files.createTempFile("jina-smoke", ".md");
		Files.writeString(localFile, "# 本地文档\n\njina 本地上传", StandardCharsets.UTF_8);
		String fileResult = tool.call("{\"url\":\"" + localFile.toUri() + "\"}");
		System.out.println("      结果: " + truncate(fileResult, 200));
		boolean fileOk = fileResult != null && fileResult.contains("jina 本地上传");
		System.out.println("      判定：" + (fileOk ? "通过（file:// 本地文件上传转换返回内容）" : "失败"));
		Files.deleteIfExists(localFile);

		boolean ok = converted && ssrf && fileOk;
		System.out.println("结论：" + (ok ? "通过（代抓 + SSRF 拦截 + file:// 上传 全部可用）"
			: "未通过（见上方输出）"));
	}

	/** 就绪探测：POST 真实代抓（example.com），返回 markdown 含 "Example Domain" 即引擎 HTTP 就绪。 */
	private static boolean probeReady(String baseUrl) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
				.timeout(PROBE_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + EXAMPLE_URL + "\"}",
					StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response =
				client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return response.statusCode() < 400 && response.body() != null && response.body().contains("Example Domain");
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static String truncate(String text, int limit) {
		if (text == null) {
			return "null";
		}
		return text.length() <= limit ? text : text.substring(0, limit) + "…";
	}

}
