package io.xyz.xyz_mcp_hub.docker;

/**
 * 容器接入协议（ContainerSpec.protocol，ADR-0011 决策 4）：{@code mcp} 型转发容器内 MCP 工具
 * （如 markitdown-mcp）；{@code rest} 型由 JVM 薄包装容器 REST API（如 jina）。
 */
public enum Protocol {

	MCP,
	REST;

	/** 由清单字符串解析（大小写不敏感）；未知取值抛 {@link IllegalArgumentException}。 */
	public static Protocol parse(String value) {
		if (value == null) {
			throw new IllegalArgumentException("protocol 不能为空（取值 mcp | rest）");
		}
		return switch (value.trim().toLowerCase()) {
			case "mcp" -> MCP;
			case "rest" -> REST;
			default -> throw new IllegalArgumentException("未知 protocol：" + value + "（取值 mcp | rest）");
		};
	}
}
