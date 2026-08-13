package io.xyz.xyz_mcp_hub.jina;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jina 顶级模块的 Spring 配置（ADR-0016 配置化）：从 {@code jina.url} 构建纯能力 {@link JinaReader}。
 *
 * <p>端点由 compose 部署（如 {@code http://127.0.0.1:18081}），dev/prod 差异走 profile 注入
 * （ADR-0005）。{@code jina.url} 未配置（空白）时 {@code JinaReader} 仍为 bean、{@code isAvailable()}
 * 返回 false——源已注册、目录列出 {@code enabled=false}、工具为空（#50 注册/启用分离，优雅降级）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class JinaConfig {

	@Bean
	JinaReader jinaReader(@Value("${jina.url:}") String url) {
		return new JinaReader(url);
	}

}
