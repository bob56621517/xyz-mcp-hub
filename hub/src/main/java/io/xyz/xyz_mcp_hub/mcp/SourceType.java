package io.xyz.xyz_mcp_hub.mcp;

import java.util.Locale;

/**
 * 源类型（目录 API 元数据，ADR-0011 / issue #34）：目录中每个源的 {@code type} 字段取值。
 *
 * <ul>
 * <li>{@link #NATIVE} — 原生 MCP：Hub JVM 内薄实现（包装 HTTP API/SDK，如 bocha / utils）</li>
 * <li>{@link #PROXY} — 代理 MCP：转发公有云 HTTP MCP Server（工具启动时发现）</li>
 * <li>{@link #CONTAINER} — 容器 MCP：本地容器按需拉起后接入（{@code protocol: mcp | rest}）</li>
 * <li>{@link #HOST} — 主机 MCP：必须与 Agent/CLI 同宿主的服务（薄实现原则的例外）</li>
 * </ul>
 *
 * <p>目录 JSON 中序列化为小写 {@link #value()}（native/proxy/container/host），与 ADR-0011
 * 目录 schema 一致。作为 {@link McpEndpointProvider#getSourceType()} 的返回类型，是
 * #35/#36/#37（proxy / container 迁入注册表）共用的元数据模型，一次定稿。</p>
 */
public enum SourceType {

	NATIVE,
	PROXY,
	CONTAINER,
	HOST;

	/** 目录 JSON 中的小写取值（native/proxy/container/host）。 */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
