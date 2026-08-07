package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

/**
 * fetch 端点业务异常：抓取失败、重定向超限、PDF 解析失败等。
 * 由 {@link FetchTools} 捕获并转为友好提示文本返回。
 */
public class FetchException extends RuntimeException {

	public FetchException(String message) {
		super(message);
	}

	public FetchException(String message, Throwable cause) {
		super(message, cause);
	}
}
