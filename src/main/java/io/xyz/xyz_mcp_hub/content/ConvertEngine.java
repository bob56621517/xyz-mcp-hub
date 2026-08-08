package io.xyz.xyz_mcp_hub.content;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * 内容转换统一入口：{@code convert(bytes, format) → Markdown}。按格式标识调度已注册的
 * {@link FormatConverter}，无匹配实现时抛 {@link UnsupportedFormatException}。
 *
 * <p>当前为调度骨架：markitdown 转换后端在 issue #25 接入，届时各格式实现注册为
 * {@link FormatConverter} bean 即可经此入口转换，fetch 适配层无需感知实现细节。</p>
 */
@Component
public class ConvertEngine {

	private final List<FormatConverter> converters;

	public ConvertEngine(List<FormatConverter> converters) {
		this.converters = converters;
	}

	public String convert(byte[] bytes, String format) {
		if (bytes == null) {
			throw new IllegalArgumentException("bytes 不能为空");
		}
		String key = (format == null ? "" : format).toLowerCase(Locale.ROOT);
		return converters.stream()
			.filter(c -> c.supportedFormats().contains(key))
			.findFirst()
			.orElseThrow(() -> new UnsupportedFormatException(format))
			.convert(bytes, format);
	}
}
