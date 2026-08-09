package io.xyz.xyz_mcp_hub.mcp.internal.space;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.SpaceDefinition;
import io.xyz.xyz_mcp_hub.mcp.SpaceDefinitionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 为配置声明的每个 Space 动态注册一个 {@link SpaceMcpProvider} bean。
 *
 * <p>实现 {@link BeanDefinitionRegistryPostProcessor}，在 bean 定义后处理阶段读取
 * {@code mcp.spaces} 配置并注册提供者（每个 Space 一个端点），随后由
 * {@code HubMcpRegistrar} 总线统一注册。提供者的构造参数经 {@link RuntimeBeanReference}
 * 引用容器内全部 {@link SpaceDefinitionSource} bean（本任务为 YAML 来源），在正常实例化
 * 阶段解析——未来 DB 来源只需注册为 bean 即自动并入（ADR-0008 抽象层）。未声明来源的
 * Space 跳过（缺配置不注册）。</p>
 *
 * <p>注意：post-processor bean 在 autowire 注册前实例化，不能用构造器注入，故在
 * {@link #postProcessBeanFactory} 阶段经 beanFactory 获取 Environment 读配置。</p>
 */
@Component
public class SpaceEndpointRegistrar implements BeanDefinitionRegistryPostProcessor {

	private static final Logger log = LoggerFactory.getLogger(SpaceEndpointRegistrar.class);

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		// 注册动作在 postProcessBeanFactory 阶段执行（该阶段 beanFactory/Environment 可用）
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
			log.warn("beanFactory 不是 BeanDefinitionRegistry，跳过 Space 动态注册");
			return;
		}
		String[] sourceBeanNames = beanFactory.getBeanNamesForType(SpaceDefinitionSource.class);
		Environment environment = beanFactory.getBean(Environment.class);
		List<SpaceDefinition> definitions = new YamlSpaceDefinitionSource(environment).load();
		if (sourceBeanNames.length == 0) {
			log.warn("no SpaceDefinitionSource, skip space registration");
			return;
		}

		ManagedList<RuntimeBeanReference> sourceRefs = new ManagedList<>();
		for (String beanName : sourceBeanNames) {
			sourceRefs.add(new RuntimeBeanReference(beanName));
		}
		for (SpaceDefinition definition : definitions) {
			if (definition.sources().isEmpty()) {
				log.warn("Space {} 未声明 sources，跳过注册", definition.name());
				continue;
			}
			registry.registerBeanDefinition("spaceMcpProvider$" + definition.name(),
					BeanDefinitionBuilder.genericBeanDefinition(SpaceMcpProvider.class)
						.addConstructorArgValue(sourceRefs)
						.addConstructorArgValue(definition.name())
						.getBeanDefinition());
		}
	}

}
