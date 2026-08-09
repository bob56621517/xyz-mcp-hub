package io.xyz.xyz_mcp_hub.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link MarkitdownServer} 子进程生命周期单测：命令无效快速失败且不重试、空命令拒绝、
 * host 固定 localhost 安全约束。纯 JVM、无 Spring、无外部服务依赖。
 */
class MarkitdownServerTest {

	private static MarkitdownServer serverWith(String command) {
		MarkitdownProperties props = new MarkitdownProperties();
		props.setEnabled(true);
		props.setCommand(command);
		return new MarkitdownServer(props);
	}

	@Test
	void invalidCommandFailsFastAndDoesNotRetry() {
		MarkitdownServer server = serverWith("nonexistent-command-xyz-123");
		assertThatThrownBy(server::ensureStarted)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("启动 markitdown");
		assertThat(server.isRunning()).isFalse();
		// 启动失败后不重试：再次调用立即抛「不可用」，而非等 30s 端口超时
		assertThatThrownBy(server::ensureStarted)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("不可用");
	}

	@Test
	void blankCommandRejected() {
		MarkitdownServer server = serverWith("   ");
		assertThatThrownBy(server::ensureStarted)
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void endpointUrlUsesConfiguredPortAndFixedHost() {
		MarkitdownProperties props = new MarkitdownProperties();
		props.setPort(3456);
		assertThat(props.endpointUrl()).isEqualTo("http://127.0.0.1:3456/mcp/");
		// host 安全硬约束：连接地址固定 127.0.0.1，配置对象无 host 可配项
		assertThat(MarkitdownProperties.HOST).isEqualTo("localhost");
	}

	@Test
	void defaultPortAndCommandFromSpec() {
		MarkitdownProperties props = new MarkitdownProperties();
		assertThat(props.getPort()).isEqualTo(3001);
		assertThat(props.getCommand())
			.isEqualTo("uvx --with mcp<2.0.0 markitdown-mcp --http --port 3001 --host localhost");
		assertThat(props.isEnabled()).isFalse();
		assertThat(props.isAutoStart()).isTrue();
	}

}
