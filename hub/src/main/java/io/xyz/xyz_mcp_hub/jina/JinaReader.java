package io.xyz.xyz_mcp_hub.jina;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * jina 顶级模块的纯能力 Reader（ADR-0016 配置化，取代容器物化）：把网页/PDF（http(s)）或本地文件
 * （file://）解析为 Markdown 正文。零 MCP/Spring AI 依赖，端点来自配置 {@code jina.url}
 * （compose 部署，如 {@code http://127.0.0.1:18081}）。
 *
 * <p>http(s) URL：POST {@code {"url":...}} 到端点根路径代抓（{@link JinaRestClient#postJson}），返回
 * markdown。file:// 本地文件：**坐标 = hub 宿主文件系统**（ADR-0016 的 file:// 语义）——读宿主文件字节，
 * 经 multipart {@code file} 字段上传（{@link JinaRestClient#uploadFile}）转换（jina 按字节嗅探 MIME，
 * 支持 pdf/docx/xlsx/pptx 及文本）。</p>
 *
 * <p>优雅降级：{@code jina.url} 未配置（空白）时 {@link #isAvailable()} 返回 {@code false}——源仍已注册、
 * 目录列出 {@code enabled=false}、工具为空（见 {@code JinaTools}）。调用路径缺失端点时抛异常说明，
 * 正常不应发生（isEnabled 已门控）。</p>
 *
 * <p><strong>能力边界</strong>：file:// 读取宿主文件系统（与 HostMcp 同类语义）。调用方（MCP 工具层）负责
 * 访问策略与 scheme 路由；http(s) 走 SSRF 预检（ADR-0010），file:// 是本地读取非网络代抓、无 SSRF 面。</p>
 */
public class JinaReader {

	/** 源名（目录源名与 jina 配置键）。 */
	static final String SOURCE_NAME = "jina";

	private final String baseUrl;
	private final JinaRestClient restClient;

	public JinaReader(String baseUrl) {
		this.baseUrl = baseUrl;
		this.restClient = new JinaRestClient(baseUrl);
	}

	/** 源是否可用：{@code jina.url} 已配置（非空白）。 */
	public boolean isAvailable() {
		return baseUrl != null && !baseUrl.isBlank();
	}

	/** http(s) 网页/PDF 代抓：POST url 到端点 → 返回 markdown。 */
	public String readUrl(String url) {
		requireAvailable();
		return restClient.postJson("", requestBody(url));
	}

	/** file:// 本地文件解析：读宿主文件 → multipart 上传 → 返回转换后的 markdown。 */
	public String readLocalFile(String fileUri) {
		requireAvailable();
		URI uri;
		try {
			uri = URI.create(fileUri);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("非法 file:// 路径：" + fileUri, e);
		}
		if (uri.getScheme() == null || !"file".equalsIgnoreCase(uri.getScheme()) || uri.getHost() != null) {
			throw new IllegalArgumentException("仅支持本地 file:// 路径（无主机）：" + fileUri);
		}
		Path path = Path.of(uri);
		byte[] content;
		try {
			content = Files.readAllBytes(path);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取本地文件失败：" + path, e);
		}
		String filename = path.getFileName() == null ? "file" : path.getFileName().toString();
		return restClient.uploadFile(content, filename);
	}

	private void requireAvailable() {
		if (!isAvailable()) {
			throw new IllegalStateException("jina 端点未配置（jina.url，见 ADR-0016）");
		}
	}

	/** JSON 请求体（url 内嵌引号/反斜杠需转义）。 */
	private static String requestBody(String url) {
		return "{\"url\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}
}
