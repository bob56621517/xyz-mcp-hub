package io.xyz.xyz_mcp_hub.jina;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jina reader 的 REST 转发客户端（ADR-0016 配置化，取代容器物化）：把一次 HTTP 请求转发到
 * jina reader 端点（{@code jina.url}，compose 部署，如 {@code http://127.0.0.1:18081}），
 * 返回响应体文本（markdown）。
 *
 * <p>能力：{@code postJson}（http(s) 代抓，POST {@code {"url":...}} 到根路径）与
 * {@code uploadFile}（本地文件转换，multipart {@code file} 字段上传，jina 按字节嗅探 MIME——
 * 支持 pdf/docx/xlsx/pptx 及文本，见 jina-reader 文档）。容器/上游生命周期由 compose 承担，
 * 本类不做任何编排。</p>
 *
 * <p>连接层失败（{@link IOException}，含引擎启动窗口的「no bytes」）短重试覆盖临时抖动；
 * HTTP 非 2xx（服务已就绪但返回错误）与线程中断不重试。非 2xx 抛 {@link IllegalStateException}
 * （携带截断后的响应体便于定位），由工具层捕获转为友好文本。</p>
 */
public class JinaRestClient {

	private static final Logger log = LoggerFactory.getLogger(JinaRestClient.class);

	/** 单次请求超时（jina 真实浏览器渲染 + 内容抓取较慢，放宽到 60s）。 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

	/** 建连超时（本地映射建连快）。 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** 错误信息中响应体最大长度（过长截断）。 */
	private static final int ERROR_BODY_LIMIT = 500;

	/** 连接层失败重试次数（含首次）。引擎由 compose 管理，启动窗口小，短重试覆盖临时抖动即可。 */
	private static final int CALL_RETRIES = 5;

	/** 重试间隔（毫秒）。 */
	private static final long RETRY_DELAY_MS = 1_000;

	/** multipart 上传的 file 字段名（jina-reader 约定）。 */
	private static final String FILE_FIELD = "file";

	/** 输出保留全部图片 URL（ADR-0016 决策 7：hub 侧 vision 工具需要图 URL，jina 不裁剪图）。 */
	private static final String RETAIN_IMAGES = "X-Retain-Images";

	private final String baseUrl;
	private final HttpClient http;

	/** @param baseUrl jina reader 端点（如 {@code http://127.0.0.1:18081}）；空白允许（源未启用时不被调用）。 */
	public JinaRestClient(String baseUrl) {
		String raw = baseUrl == null ? "" : baseUrl;
		this.baseUrl = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
		this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
	}

	/**
	 * POST JSON 请求到端点（根路径 {@code ""} 如 jina 的 {@code POST /}），返回响应体（UTF-8 文本）。
	 *
	 * @param path 端点路径（默认根路径传 {@code ""}）
	 * @param jsonBody JSON 请求体（如 {@code {"url": "https://example.com"}}）
	 */
	public String postJson(String path, String jsonBody) {
		URI uri = URI.create(requireBase() + path);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.header(RETAIN_IMAGES, "all")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
			.build();
		return sendWithRetry(request);
	}

	/**
	 * 上传文件（multipart {@code file} 字段）到端点根路径，返回转换后的 markdown。
	 *
	 * <p>jina 按字节嗅探 MIME：{@code file} 字段接受 pdf/docx/xlsx/pptx 及文本；Multipart 流式传输，
	 * 无 base64 膨胀。本地文件坐标 = hub 宿主文件系统（ADR-0016，调用方读文件后传字节）。</p>
	 *
	 * @param content 文件字节
	 * @param filename 文件名（multipart filename 字段，jina 据此/字节嗅探）
	 */
	public String uploadFile(byte[] content, String filename) {
		URI uri = URI.create(requireBase());
		String boundary = "----hub" + Long.toHexString(System.nanoTime());
		byte[] preamble = ("--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"" + FILE_FIELD + "\"; filename=\""
			+ sanitizeFilename(filename) + "\"\r\n"
			+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.header(RETAIN_IMAGES, "all")
			.POST(HttpRequest.BodyPublishers.concat(
				HttpRequest.BodyPublishers.ofByteArray(preamble),
				HttpRequest.BodyPublishers.ofByteArray(content),
				HttpRequest.BodyPublishers.ofByteArray(epilogue)))
			.build();
		return sendWithRetry(request);
	}

	/** 端点未配置时快速失败（源未启用不应被调用，兜底说明）。 */
	private String requireBase() {
		if (baseUrl.isBlank()) {
			throw new IllegalStateException("jina 端点未配置（jina.url，见 ADR-0016）");
		}
		return baseUrl;
	}

	/** multipart filename 去 CR/LF（防 header 注入）与引号。 */
	private static String sanitizeFilename(String filename) {
		if (filename == null) {
			return "file";
		}
		return filename.replace("\r", "").replace("\n", "").replace("\"", "'");
	}

	/** 连接层失败重试；HTTP 非 2xx / 中断不重试。 */
	private String sendWithRetry(HttpRequest request) {
		Exception lastError = null;
		for (int attempt = 1; attempt <= CALL_RETRIES; attempt++) {
			try {
				return sendOnce(request);
			}
			catch (IOException e) {
				lastError = e;
				// 连接不可达（ConnectException/UnknownHostException，含连接超时）不是抖动：端点没起，重试也白费
				if (isUnreachable(e)) {
					break;
				}
				log.warn("调用 jina 端点失败（第 {}/{} 次，将重试）：{}", attempt, CALL_RETRIES, e.getMessage());
				if (attempt < CALL_RETRIES) {
					sleep(RETRY_DELAY_MS);
				}
			}
		}
		throw new IllegalStateException("调用 jina 端点失败（" + CALL_RETRIES
			+ " 次重试后仍失败）：" + (lastError == null ? "" : lastError.getMessage()), lastError);
	}

	/** 单次请求；连接层失败抛 {@link IOException}（由调用方重试），HTTP 非 2xx / 中断抛异常不重试。 */
	private String sendOnce(HttpRequest request) throws IOException {
		final HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("调用 jina 端点被中断：" + request.uri(), e);
		}
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("jina 返回 HTTP " + response.statusCode()
				+ "：" + truncate(response.body()));
		}
		return response.body();
	}

	/** 连接不可达（vs 启动窗口的 no bytes）：异常链含 ConnectException / UnknownHostException 即判定不可达。 */
	private static boolean isUnreachable(IOException e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
				return true;
			}
		}
		return false;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String truncate(String body) {
		if (body == null) {
			return "";
		}
		String trimmed = body.strip();
		return trimmed.length() <= ERROR_BODY_LIMIT
			? trimmed
			: trimmed.substring(0, ERROR_BODY_LIMIT) + "…";
	}
}
