package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import io.xyz.xyz_mcp_hub.docker.ContainerSpec;

/**
 * ContainerMcp 的容器端点解析 seam（#37 mcp 型 / #38 rest 型）：{@link ContainerSpec} → 容器服务端点 URL。
 *
 * <p>默认解析为宿主导航地址——容器绑 {@code 127.0.0.1}、放隔离网络（ADR-0010/0012），Hub 只能经宿主
 * 映射端口到达容器端点。mcp 型（{@link #mcpUrl}）：路径固定 {@code /mcp/}（镜像内 streamable HTTP
 * 端点，尾斜杠必须：sidecar 的 FastMCP Mount 在 {@code /mcp}，请求 {@code /mcp} 会 307 重定向到
 * {@code /mcp/}，而 Java HTTP client 默认不跟随 POST 重定向，真实冒烟实测见 #37）；rest 型
 * （{@link #restUrl}）：JVM 薄包装容器 REST API（如 jina），根路径 {@code /}。</p>
 *
 * <p>独立为函数式 seam 便于测试注入指向内嵌模拟上游的解析器，不依赖真实 docker。</p>
 */
@FunctionalInterface
public interface ContainerEndpoint {

	/** spec → 容器 MCP 服务完整端点 URL（如 {@code http://127.0.0.1:13001/mcp/}）。 */
	String mcpUrl(ContainerSpec spec);

	/** spec → 容器 REST API 根端点 URL（如 {@code http://127.0.0.1:18081/}；rest 型容器，默认 {@code /}）。 */
	default String restUrl(ContainerSpec spec) {
		return "http://127.0.0.1:" + spec.hostPort() + "/";
	}

	/** 默认解析：宿主导航到容器宿主映射端口（mcp 型路径 {@code /mcp/}，rest 型默认根路径）。 */
	static ContainerEndpoint hostPort() {
		return spec -> "http://127.0.0.1:" + spec.hostPort() + "/mcp/";
	}
}
