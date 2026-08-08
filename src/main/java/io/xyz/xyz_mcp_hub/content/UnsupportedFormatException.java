package io.xyz.xyz_mcp_hub.content;

/** 请求的转换格式没有已注册的 {@link FormatConverter} 实现。 */
public class UnsupportedFormatException extends RuntimeException {

	public UnsupportedFormatException(String format) {
		super("暂不支持转换格式：" + (format == null || format.isBlank() ? "（空）" : format));
	}
}
