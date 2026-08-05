package io.xyz.xyz_mcp_hub.mcp.internal.proxy;

/**
 * 代理 MCP 的拦截器扩展点 —— 预留。
 *
 * <p>ProxyMcp 作为 MCP Client 透明代理已有官方公有云 MCP Server 时，可在调用前后挂接拦截逻辑
 * （日志记录、速率限制等）。本期仅定义空钩子，不实现任何具体逻辑；后续接入具体代理服务时再填充。</p>
 */
public interface ProxyInterceptor {

	/**
	 * 转发调用前触发。当前为空实现。
	 */
	default void onBefore() {
	}

	/**
	 * 转发调用结束后触发。当前为空实现。
	 */
	default void onAfter() {
	}

}
