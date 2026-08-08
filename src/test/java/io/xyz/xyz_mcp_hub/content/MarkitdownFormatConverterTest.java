package io.xyz.xyz_mcp_hub.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import io.xyz.mcp.testproxy.MarkitdownUpstreamApplication;
import io.xyz.mcp.testproxy.MarkitdownUpstreamRegistrar;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@link MarkitdownFormatConverter} 转发链路单测：内嵌 mock MCP Server（暴露
 * {@code convert_to_markdown(uri)}，返回固定 Markdown、记录收到的 uri），Mockito 模拟
 * {@link MarkitdownServer} 指向内嵌上游。无外部依赖、不起真实 markitdown 子进程。
 */
class MarkitdownFormatConverterTest {

	private static ConfigurableApplicationContext upstreamContext;
	private static int upstreamPort;

	@BeforeAll
	static void startUpstream() {
		upstreamContext = new SpringApplicationBuilder(MarkitdownUpstreamApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0")
			.run();
		upstreamPort = ((WebServerApplicationContext) upstreamContext).getWebServer().getPort();
	}

	@AfterAll
	static void stopUpstream() {
		if (upstreamContext != null) {
			upstreamContext.close();
		}
	}

	@BeforeEach
	void resetLastUri() {
		MarkitdownUpstreamRegistrar.LAST_URI.set(null);
	}

	private static MarkitdownFormatConverter converter(String endpointUrl) {
		MarkitdownServer server = mock(MarkitdownServer.class);
		when(server.endpointUrl()).thenReturn(endpointUrl);
		return new MarkitdownFormatConverter(server);
	}

	private static MarkitdownFormatConverter converter() {
		return converter("http://localhost:" + upstreamPort + "/mcp/server/markitdown");
	}

	@Test
	void convertReturnsMarkdownFromUpstream() {
		String result = converter().convert("<h1>hi</h1>".getBytes(StandardCharsets.UTF_8), "html");
		assertThat(result).isEqualTo("# mock 转换结果\n\nmarkdown 正文");
	}

	@Test
	void convertForwardsFileUriWithFormatExtension() {
		converter().convert("<p>hello</p>".getBytes(StandardCharsets.UTF_8), "pdf");
		assertThat(MarkitdownUpstreamRegistrar.LAST_URI.get())
			.startsWith("file://")
			.endsWith(".pdf");
	}

	@Test
	void supportedFormatsCoverMarkitdownScope() {
		assertThat(converter().supportedFormats())
			.contains("html", "pdf", "docx", "xlsx", "pptx", "epub", "csv", "json", "md");
	}

	@Test
	void nullBytesRejected() {
		assertThatThrownBy(() -> converter().convert(null, "html"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bytes");
	}

	@Test
	void invalidEndpointThrows() {
		// 无法解析的端点：连接初始化失败，异常传播（快速失败，不依赖网络超时）
		assertThatThrownBy(() -> converter("::invalid").convert("x".getBytes(StandardCharsets.UTF_8), "html"))
			.isInstanceOf(RuntimeException.class);
	}

}
