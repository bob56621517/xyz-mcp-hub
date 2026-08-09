package io.xyz.xyz_mcp_hub.content;

import java.util.Set;

/**
 * 内容转换 SPI：把某一格式的字节流转为 Markdown。实现方声明支持的格式标识，
 * 由 {@link ConvertEngine} 按格式统一调度；本地实现与 markitdown 转发实现均实现此接口。
 */
public interface FormatConverter {

	/** 本转换器支持的格式标识集合（小写，如 {@code "html"} / {@code "pdf"} / {@code "docx"}）。 */
	Set<String> supportedFormats();

	/** 把 {@code bytes} 按指定格式转换为 Markdown；bytes 为 null 或格式不支持时抛异常。 */
	String convert(byte[] bytes, String format);
}
