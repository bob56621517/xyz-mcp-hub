package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 目录 API（ADR-0011 / issue #34）：{@code GET /xyz-hub/catalog} 机器可读的「源 + 工具」清单，
 * URL 构建器与任意客户端的枚举事实源。
 *
 * <p>每源：{@code name} / {@code type}（native/proxy/container/host/composite，小写）/
 * {@code protocol}（container 专有，mcp|rest，其余为 null）/ {@code scope}（host/network，小写）/
 * {@code tools}（带 {@code {source}_} 前缀的注册工具名，排序稳定）/ {@code base}（组合源溯源，
 * 非组合源为 null，#33 起组合源填充）。</p>
 *
 * <p>数据三源汇合（代码声明 + 静态冒烟 + 启动发现）：本期目录反映源注册表（{@link McpSourceRegistry}）
 * 中已注册的「代码声明」源（当前为 native 源）；目录直接读注册表，proxy / container 源迁入并注册进
 * 注册表后（注册门槛是 {@link McpSourceRegistry} 构造时的源过滤）即自动出现在目录（验收允许）。
 * 无认证、仅本地可读（与 MCP 端点一致）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class CatalogEndpoint {

	/** 目录端点路径。 */
	public static final String CATALOG_PATH = "/xyz-hub/catalog";

	private final McpSourceRegistry registry;

	public CatalogEndpoint(McpSourceRegistry registry) {
		this.registry = registry;
	}

	/**
	 * 目录路由：与 MCP 单端点路由（{@code McpSingleEndpointRegistrar}）由 Spring 合并为同一
	 * {@code RouterFunctionMapping}。
	 */
	@Bean
	RouterFunction<ServerResponse> xyzHubCatalogRouterFunction() {
		return RouterFunctions.route()
			.GET(CATALOG_PATH, request -> ServerResponse.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body(toCatalog()))
			.build();
	}

	/** 当前注册表快照 → 目录响应体（顶层包装，便于后续追加版本等字段）。 */
	private Catalog toCatalog() {
		return new Catalog(registry.sources().stream()
			.map(CatalogEndpoint::map)
			.toList());
	}

	/**
	 * 源 → 目录条目（包级可见供纯单测验证非空 protocol / base 的序列化映射，见
	 * {@code CatalogEndpointTest}）。
	 */
	static CatalogSource map(McpSourceRegistry.McpSource source) {
		return new CatalogSource(
				source.name(),
				source.type().value(),
				source.protocol() == null ? null : source.protocol().name().toLowerCase(Locale.ROOT),
				source.scope().name().toLowerCase(Locale.ROOT),
				source.specs().stream().map(spec -> spec.tool().name()).sorted().toList(),
				source.base());
	}

	/** 目录响应体：{@code {"sources": [...]}}。 */
	public record Catalog(List<CatalogSource> sources) {
	}

	/** 单个源的目录条目（机器可读，字段与 ADR-0011 目录 schema 一致）。 */
	public record CatalogSource(
			String name,
			String type,
			String protocol,
			String scope,
			List<String> tools,
			CompositeBase base) {
	}

}
