package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Bocha 端点配置：为博查 HTTP API 构建带 base-url 与 Bearer 认证头的 {@link RestClient}。
 *
 * <p>Spring Boot 4 不再自动配置 {@code RestClient.Builder}，故在此显式声明。</p>
 */
@Configuration(proxyBeanMethods = false)
public class BochaConfig {

	@Bean
	RestClient bochaRestClient(
			@Value("${bocha.base-url:https://api.bochaai.com}") String baseUrl,
			@Value("${bocha.api-key:}") String apiKey) {
		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
	}

}
