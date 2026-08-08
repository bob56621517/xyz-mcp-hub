package io.xyz.xyz_mcp_hub.content;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 经 markitdown HTTP 服务（{@link MarkitdownServer}）转发的内容转换器：把本地 bytes 写入
 * 临时文件，以 {@code file://} URI 调 markitdown 的 {@code convert_to_markdown(uri)} 工具，
 * 返回 Markdown。覆盖 markitdown 支持的全部格式，无独立 MCP 端点（fetch 工具内部使用）。
 *
 * <p>MCP client 懒建立并复用（double-check），线程安全（底层 async client 按消息 id
 * 独立路由），支持多线程并发 {@link #convert}（#16 多 agent 场景）；连接中断或初始化
 * 失败时关闭旧连接，下次调用重建。转换内容失败（{@code isError}）抛
 * {@link IllegalStateException}，由调用方（fetch 门面）处理。</p>
 */
@Component
@ConditionalOnProperty(prefix = "content.markitdown", name = "enabled", havingValue = "true")
public class MarkitdownFormatConverter implements FormatConverter, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(MarkitdownFormatConverter.class);

	/** markitdown 覆盖的格式标识全集（转换质量权威在 markitdown 侧，见 issue #22）。 */
	private static final Set<String> SUPPORTED_FORMATS = Set.of(
			"html", "htm",
			"txt", "md", "markdown", "csv", "json", "xml", "yaml", "yml", "toml", "log",
			"pdf", "docx", "xlsx", "pptx", "epub",
			"png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico", "tiff", "heic",
			"mp3", "wav", "m4a", "flac", "aac", "ogg", "wma", "amr", "opus",
			"zip", "ipynb");

	private static final String TOOL_NAME = "convert_to_markdown";

	private final MarkitdownServer server;
	private final Object clientLock = new Object();

	private volatile McpSyncClient client;

	public MarkitdownFormatConverter(MarkitdownServer server) {
		this.server = server;
	}

	@Override
	public Set<String> supportedFormats() {
		return SUPPORTED_FORMATS;
	}

	@Override
	public String convert(byte[] bytes, String format) {
		if (bytes == null) {
			throw new IllegalArgumentException("bytes 不能为空");
		}
		server.ensureStarted();
		Path tmp = null;
		try {
			tmp = writeTempFile(bytes, format);
			String uri = tmp.toUri().toString();
			McpSyncClient c = client();
			McpSchema.CallToolResult result;
			try {
				result = c.callTool(McpSchema.CallToolRequest.builder(TOOL_NAME)
						.arguments(java.util.Map.of("uri", uri))
						.build());
			}
			catch (RuntimeException e) {
				// 连接中断：关闭旧连接，下次调用重建
				resetClient();
				throw new IllegalStateException("调用 markitdown convert_to_markdown 失败（" + uri + "）", e);
			}
			if (result.isError()) {
				throw new IllegalStateException("markitdown 转换失败（" + uri + "）：" + extractText(result));
			}
			return extractText(result);
		}
		catch (IOException e) {
			throw new IllegalStateException("写 markitdown 临时文件失败", e);
		}
		finally {
			if (tmp != null) {
				try {
					Files.deleteIfExists(tmp);
				}
				catch (IOException e) {
					log.warn("清理 markitdown 临时文件失败：{}", tmp, e);
				}
			}
		}
	}

	/** 把 bytes 写入带格式扩展名的临时文件，供 markitdown 按扩展名/内容嗅探格式。 */
	private static Path writeTempFile(byte[] bytes, String format) throws IOException {
		String ext = (format == null ? "" : format.trim().toLowerCase(Locale.ROOT));
		String suffix = ext.matches("[a-z0-9]+") ? "." + ext : ".bin";
		Path tmp = Files.createTempFile("markitdown-", suffix);
		Files.write(tmp, bytes);
		return tmp;
	}

	private McpSyncClient client() {
		McpSyncClient c = client;
		if (c != null) {
			return c;
		}
		synchronized (clientLock) {
			if (client == null) {
				URI uri = URI.create(server.endpointUrl());
				String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
				String endpoint = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/mcp" : uri.getPath();
				var transport = HttpClientStreamableHttpTransport.builder(baseUri).endpoint(endpoint).build();
				c = McpClient.sync(transport).build();
				try {
					c.initialize();
				}
				catch (RuntimeException e) {
					// 初始化失败：关闭半初始化连接，下次调用重建
					c.closeGracefully();
					throw new IllegalStateException("连接 markitdown MCP 服务失败：" + server.endpointUrl(), e);
				}
				client = c;
			}
			return client;
		}
	}

	private void resetClient() {
		McpSyncClient c;
		synchronized (clientLock) {
			c = client;
			client = null;
		}
		if (c != null) {
			c.closeGracefully();
		}
	}

	private static String extractText(McpSchema.CallToolResult result) {
		StringBuilder sb = new StringBuilder();
		for (McpSchema.Content content : result.content()) {
			if (content instanceof McpSchema.TextContent text) {
				sb.append(text.text());
			}
		}
		return sb.toString();
	}

	@Override
	public void destroy() {
		resetClient();
	}

}
