package io.xyz.xyz_mcp_hub;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha.BochaTools;
import org.springframework.web.client.RestClient;

/**
 * 真实 bocha API 冒烟验证（手动单次调用，不入测试套件）：
 * 读取环境变量 BOCHA_API_KEY，直连博查 API 调用 web_search / ai_search，
 * 有合理结果即视为打通。
 */
public class BochaRealApiSmoke {

	public static void main(String[] args) {
		String key = System.getenv("BOCHA_API_KEY");
		if (key == null || key.isBlank()) {
			System.err.println("未设置环境变量 BOCHA_API_KEY");
			return;
		}
		RestClient client = RestClient.builder()
			.baseUrl("https://api.bochaai.com")
			.defaultHeader("Authorization", "Bearer " + key)
			.build();
		BochaTools tools = new BochaTools(client);

		System.out.println("===== web_search =====");
		System.out.println(tools.webSearch("Spring Boot", 3, "noLimit"));
		System.out.println();
		System.out.println("===== ai_search =====");
		System.out.println(tools.aiSearch("Spring Boot", 3, "noLimit"));
	}

}
