package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContainerMcp 的 REST 转发客户端（protocol=rest 型，#38）：把一次 HTTP 请求转发到容器内 REST API，
 * 返回响应体文本（如 jina reader 的 markdown）。
 *
 * <p>容器生命周期（首用拉起 / 防重拉 / 闲置回收 / 关闭销毁）由 {@code docker} 模块的
 * {@code ContainerManager} 管理，本类只在调用时 {@code ensureRunning} 拉起/复用容器再发请求——
 * 每次调用独立连接，无跨调用残留。</p>
 *
 * <p>非 2xx 响应抛 {@link IllegalStateException}（携带截断后的响应体便于定位），网络失败同样抛；
 * 由工具层捕获转为友好文本（见 {@code JinaTools}）。</p>
 */
public class ContainerRestClient {

	private static final Logger log = LoggerFactory.getLogger(ContainerRestClient.class);

	/** 单次请求超时（jina 真实浏览器渲染 + 内容抓取较慢，放宽到 60s）。 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

	/** 建连超时（容器端口已探活，本地映射建连快）。 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** 错误信息中响应体最大长度（过长截断）。 */
	private static final int ERROR_BODY_LIMIT = 500;

	private final ContainerEndpoint endpoint;
	private final ContainerManager containerManager;
	// 共享连接：容器 REST 调用低频，HttpClient 实例复用即可（连接池默认按 host 复用）
	private final HttpClient http;

	/** @param containerManager 首用拉起 + 幂等复用容器（可为 null，测试注入 fake 场景由 fake 承担） */
	public ContainerRestClient(ContainerEndpoint endpoint, ContainerManager containerManager) {
		this.endpoint = endpoint;
		this.containerManager = containerManager;
		this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
	}

	/**
	 * 向容器 REST 端点发送 POST JSON 请求，返回响应体（UTF-8 文本）。
	 *
	 * @param path 端点路径（默认根路径传 {@code ""}，如 jina 的 {@code POST /}）
	 * @param jsonBody JSON 请求体（如 {@code {"url": "https://example.com"}}）
	 */
	public String postJson(ContainerSpec spec, String path, String jsonBody) {
		if (containerManager != null) {
			containerManager.ensureRunning(spec);
		}
		URI uri = URI.create(endpoint.restUrl(spec) + path);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<String> response =
				http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() >= 400) {
				throw new IllegalStateException("容器 " + spec.name() + " 返回 HTTP " + response.statusCode()
					+ "：" + truncate(response.body()));
			}
			return response.body();
		}
		catch (IOException e) {
			log.warn("调用容器 {} REST 端点失败（{}）：{}", spec.name(), uri, e.getMessage());
			throw new IllegalStateException("调用容器 " + spec.name() + " REST 端点失败（" + uri + "）：" + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("调用容器 " + spec.name() + " REST 端点被中断：" + uri, e);
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
