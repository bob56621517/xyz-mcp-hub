package io.xyz.xyz_mcp_hub.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * markitdown 启用开关测试：{@code content.markitdown.enabled=false}（测试默认）时不创建
 * {@link MarkitdownServer} bean、不注册转换器、转换抛 {@link UnsupportedFormatException}；
 * {@code enabled=true + autoStart=false} 时不自动拉起子进程。
 *
 * <p>普通测试（含本类外层）经 {@code src/test/resources/application.properties} 设
 * {@code enabled=false}，避免每个 @SpringBootTest 上下文拉起 markitdown 子进程。</p>
 */
@SpringBootTest
class MarkitdownEnabledDisabledTest {

	@Autowired(required = false)
	private MarkitdownServer server;

	@Autowired
	private ConvertEngine engine;

	@Test
	void disabledDoesNotCreateServerBean() {
		assertThat(server).isNull();
	}

	@Test
	void disabledConvertThrowsUnsupported() {
		assertThatThrownBy(() -> engine.convert("<h1>x</h1>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "html"))
			.isInstanceOf(UnsupportedFormatException.class);
	}

	/**
	 * enabled=true 但 autoStart=false：bean 创建但启动时不拉起子进程（懒启动语义）。
	 */
	@Nested
	@SpringBootTest
	@TestPropertySource(properties = {
			"content.markitdown.enabled=true",
			"content.markitdown.autoStart=false",
			"content.markitdown.port=38991"
	})
	class EnabledWithoutAutoStart {

		@Autowired(required = false)
		private MarkitdownServer server;

		@Test
		void serverBeanExistsButNotLaunched() {
			assertThat(server).isNotNull();
			assertThat(server.isRunning()).isFalse();
		}

	}

}
