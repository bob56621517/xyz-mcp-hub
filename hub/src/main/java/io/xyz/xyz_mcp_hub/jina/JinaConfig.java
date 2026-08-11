package io.xyz.xyz_mcp_hub.jina;

import io.xyz.xyz_mcp_hub.docker.ContainerEndpoint;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jina 顶级模块的 Spring 配置（#53 提升顶级模块）：为纯能力 {@link JinaReader} 装配容器依赖。
 *
 * <p>{@code docker.enabled=false} 时 {@link ContainerManager} bean 缺失（@ConditionalOnProperty），
 * 经 {@link ObjectProvider} 缺省为 null——JinaReader 构造不依赖容器，{@code isAvailable()} 返回
 * false 使源未启用（目录列出 enabled=false）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class JinaConfig {

	@Bean
	JinaReader jinaReader(ObjectProvider<ContainerManager> containerManagerProvider, ContainerSpecReader specReader) {
		ContainerManager containerManager = containerManagerProvider.getIfAvailable(() -> null);
		return new JinaReader(containerManager, specReader, ContainerEndpoint.hostPort());
	}

}
