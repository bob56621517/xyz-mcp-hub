package io.xyz.xyz_mcp_hub.bocha;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * bocha 顶级模块的 Spring 配置（#53 提升顶级模块）：为纯能力 {@link BochaClient} 装配带 base-url（配置键
 * {@code bocha.url}）与 Bearer 认证头的 {@link RestClient}。
 *
 * <p>Spring Boot 4 不再自动配置 {@code RestClient.Builder}，故在此显式声明。</p>
 */
@Configuration(proxyBeanMethods = false)
public class BochaConfig {

	@Bean
	RestClient bochaRestClient(
			@Value("${bocha.url:https://api.bochaai.com}") String baseUrl,
			@Value("${bocha.api-key:}") String apiKey) {
		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
	}

	@Bean
	BochaClient bochaClient(RestClient bochaRestClient) {
		return new BochaClient(bochaRestClient);
	}

}
