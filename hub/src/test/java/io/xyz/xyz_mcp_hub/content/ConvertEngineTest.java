package io.xyz.xyz_mcp_hub.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link ConvertEngine} 调度骨架单测：格式匹配（大小写不敏感）、无匹配抛
 * {@link UnsupportedFormatException}、bytes 校验。纯 JVM、无 Spring、无网络。
 */
class ConvertEngineTest {

	private final FormatConverter html = new FormatConverter() {
		@Override
		public Set<String> supportedFormats() {
			return Set.of("html");
		}

		@Override
		public String convert(byte[] bytes, String format) {
			return "html:" + new String(bytes, StandardCharsets.UTF_8);
		}
	};

	private final ConvertEngine engine = new ConvertEngine(List.of(html));

	@Test
	void dispatchesToMatchingConverter() {
		assertThat(engine.convert("<h1>hi</h1>".getBytes(StandardCharsets.UTF_8), "html"))
			.isEqualTo("html:<h1>hi</h1>");
	}

	@Test
	void formatMatchIsCaseInsensitive() {
		assertThat(engine.convert("x".getBytes(StandardCharsets.UTF_8), "HTML")).isEqualTo("html:x");
	}

	@Test
	void unknownFormatThrowsUnsupported() {
		assertThatThrownBy(() -> engine.convert("x".getBytes(StandardCharsets.UTF_8), "pdf"))
			.isInstanceOf(UnsupportedFormatException.class)
			.hasMessageContaining("pdf");
	}

	@Test
	void nullBytesRejected() {
		assertThatThrownBy(() -> engine.convert(null, "html"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankFormatThrowsUnsupported() {
		assertThatThrownBy(() -> engine.convert("x".getBytes(StandardCharsets.UTF_8), null))
			.isInstanceOf(UnsupportedFormatException.class);
	}
}
