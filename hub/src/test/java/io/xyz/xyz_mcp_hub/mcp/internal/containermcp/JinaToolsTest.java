package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.jina.JinaReader;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.mcp.internal.single.McpSourceRegistry;
import io.xyz.xyz_mcp_hub.mcp.internal.single.ToolFilter;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * JinaTools 工具类即源测试（#53）：验证 {@link JinaTools} 作为 {@code McpEndpointProvider} 的源元数据
 * （name/scope/type/protocol/enabled）与 {@code reader} 工具的 scheme 路由——http(s) 走 SSRF 预检 +
 * 容器代抓、file:// 走本地解析。HTTP/文件细节由 {@code JinaReaderTest}（能力层）覆盖，此处 mock
 * {@link JinaReader} 只测工具类的路由与源语义。
 */
class JinaToolsTest {

	private final JinaReader jinaReader = mock(JinaReader.class);

	private JinaTools tools() {
		return new JinaTools(jinaReader, new SsrUrlGuard());
	}

	// ---- 源元数据（工具类即源） ----

	@Test
	void exposesSourceMetadata() {
		JinaTools tools = tools();
		assertThat(tools.getName()).isEqualTo("jina");
		assertThat(tools.getScope()).isEqualTo(Scope.NETWORK);
		assertThat(tools.getSourceType()).isEqualTo(SourceType.CONTAINER);
		assertThat(tools.getProtocol()).isEqualTo(Protocol.REST);
		// reader 单工具注册
		assertThat(tools.getTools()).hasSize(1);
	}

	@Test
	void enabledWhenReaderAvailable() {
		when(jinaReader.isAvailable()).thenReturn(true);
		assertThat(tools().isEnabled()).isTrue();
	}

	@Test
	void disabledWhenReaderUnavailable() {
		when(jinaReader.isAvailable()).thenReturn(false);
		assertThat(tools().isEnabled()).isFalse();
	}

	@Test
	void readerToolPrefixedInRegistry() {
		when(jinaReader.isAvailable()).thenReturn(true);
		McpSourceRegistry registry = new McpSourceRegistry(List.of(tools()));
		assertThat(registry.allToolNames()).containsExactly("jina_reader");
		// 源名匹配已退役（#51）：要该源全部工具写前缀通配 [jina*]，[jina]（精确工具名）匹配不到
		assertThat(registry.visibleToolNames(ToolFilter.parse(Optional.of("[jina*]"), Optional.empty())))
			.containsExactly("jina_reader");
	}

	@Test
	void disabledProviderNotInRegistry() {
		when(jinaReader.isAvailable()).thenReturn(false);
		McpSourceRegistry registry = new McpSourceRegistry(List.of(tools()));
		assertThat(registry.allToolNames()).isEmpty();
	}

	// ---- reader 路由：http(s) → SSRF 预检 + 容器代抓 ----

	@Test
	void httpUrlDelegatesToReaderAfterSsfrPass() {
		when(jinaReader.readUrl("https://example.com/page")).thenReturn("# markdown");
		// @Tool 返回 String 会被 Spring AI 结果转换器 JSON 编码（全库既有行为），用 contains 断言
		assertThat(callReader("{\"url\":\"https://example.com/page\"}")).contains("# markdown");
		verify(jinaReader).readUrl("https://example.com/page");
		verify(jinaReader, never()).readLocalFile("https://example.com/page");
	}

	@Test
	void httpPrivateUrlRejectedBeforeForwarding() {
		String result = callReader("{\"url\":\"http://127.0.0.1:8080/internal\"}");
		assertThat(result).contains("SSRF 防护拦截");
		verify(jinaReader, never()).readUrl("http://127.0.0.1:8080/internal");
	}

	// ---- reader 路由：file:// → 本地解析（不依赖容器） ----

	@Test
	void fileUrlDelegatesToLocalParsing() {
		when(jinaReader.readLocalFile("file:///tmp/doc.md")).thenReturn("# 本地文档");
		assertThat(callReader("{\"url\":\"file:///tmp/doc.md\"}")).contains("# 本地文档");
		verify(jinaReader).readLocalFile("file:///tmp/doc.md");
		verify(jinaReader, never()).readUrl("file:///tmp/doc.md");
	}

	@Test
	void unsupportedSchemeRejected() {
		String result = callReader("{\"url\":\"ftp://example.com/x\"}");
		assertThat(result).contains("不支持的 url scheme");
		verify(jinaReader, never()).readUrl(org.mockito.ArgumentMatchers.anyString());
		verify(jinaReader, never()).readLocalFile(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void blankUrlReturnsHint() {
		String result = callReader("{\"url\":\"\"}");
		assertThat(result).contains("需要 url 参数");
	}

	/** 直接调用 {@code reader} @Tool 方法（工具类即源：可 new 直接调用测试）。 */
	private String callReader(String args) {
		ToolCallback callback = tools().getTools().get(0);
		return callback.call(args);
	}

}
