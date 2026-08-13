package io.xyz.xyz_mcp_hub.mcp;

import java.util.Locale;

/**
 * 源类型（目录 API 元数据，ADR-0011 / issue #34，#50 收敛，ADR-0016 三型收敛）：目录中每个源的
 * {@code type} 字段取值。
 *
 * <ul>
 * <li>{@link #NATIVE} — 原生 MCP：Hub JVM 内薄实现（包装 HTTP API/SDK，如 bocha / jina / utils /
 *     playwright；须与 Agent/CLI 同宿主的服务也并入此型，靠 {@code scope=host} 表达部署，见 #50）</li>
 * <li>{@link #PROXY} — 代理 MCP：转发 HTTP MCP Server（公有云或自部署，工具启动时发现）</li>
 * </ul>
 *
 * <p>目录 JSON 中序列化为小写 {@link #value()}（native/proxy），与 ADR-0011 目录 schema 一致。
 * 容器型已溶解（ADR-0016：容器只是 compose 部署细节，不再是源类型，无 container）；
 * HOST / COMPOSITE / CONTAINER 均已收敛移除（#50 / #49 / #64）。</p>
 */
public enum SourceType {

	NATIVE,
	PROXY;

	/** 目录 JSON 中的小写取值（native/proxy）。 */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
