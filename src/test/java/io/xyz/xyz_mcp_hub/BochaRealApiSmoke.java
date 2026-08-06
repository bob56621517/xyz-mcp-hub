package io.xyz.xyz_mcp_hub;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha.BochaTools;
import org.springframework.web.client.RestClient;

/**
 * Bocha 真实 API 冒烟模板（手工运行，非自动测试）。
 *
 * <p>作为各服务 main 冒烟的模板示例：环境变量读凭据、步骤化 stdout 输出、
 * 结果合理判定。详见 {@code docs/testing/mcp-service-test-guide.md}。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.BochaRealApiSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web 需真实外部网络（api.bochaai.com）
 * @requires-token BOCHA_API_KEY 从环境变量读取；未设置则退出
 */
public class BochaRealApiSmoke {

	public static void main(String[] args) {
		String key = System.getenv("BOCHA_API_KEY");
		System.out.println("[1/3] 依赖检查：BOCHA_API_KEY "
				+ (key == null || key.isBlank() ? "未设置，退出" : "已设置"));
		if (key == null || key.isBlank()) {
			return;
		}

		RestClient client = RestClient.builder()
			.baseUrl("https://api.bochaai.com")
			.defaultHeader("Authorization", "Bearer " + key)
			.build();
		BochaTools tools = new BochaTools(client);

		System.out.println("[2/3] 调用 web_search(\"Spring Boot\", 3, \"noLimit\")");
		String web = tools.webSearch("Spring Boot", 3, "noLimit");
		System.out.println("      结果：\n" + truncate(web, 500));

		System.out.println("[3/3] 调用 ai_search(\"Spring Boot\", 3, \"noLimit\")");
		String ai = tools.aiSearch("Spring Boot", 3, "noLimit");
		System.out.println("      结果：\n" + truncate(ai, 500));

		boolean ok = !web.isBlank() && !ai.isBlank()
				&& !web.contains("博查搜索失败") && !ai.contains("博查搜索失败");
		System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）"));
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

}
