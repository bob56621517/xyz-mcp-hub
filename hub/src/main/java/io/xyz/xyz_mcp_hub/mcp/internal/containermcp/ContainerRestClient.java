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

	/**
	 * 启动竞态重试次数（含首次）。实测（#38 冒烟）：ContainerManager 健康检查是 TCP 探活，jina 容器
	 * TCP 端口绑定后 Node 应用（含浏览器渲染服务）仍需数秒 HTTP 就绪，立即调用会
	 * 「HTTP/1.1 header parser received no bytes」；对齐 {@code ContainerMcpClient} 的
	 * {@code INITIALIZE_RETRIES} 模式，对连接层失败重试覆盖启动窗口。
	 */
	private static final int CALL_RETRIES = 30;

	/** 重试间隔（毫秒）。 */
	private static final long RETRY_DELAY_MS = 1_000;

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
	 * <p>连接层失败（{@link IOException}，含启动竞态的「no bytes」）重试 {@code CALL_RETRIES} 次，
	 * 覆盖 jina 容器 TCP 就绪但应用未就绪的窗口；每次重试前 {@code ensureRunning}（touch 容器防短 TTL
	 * 回收）。HTTP 非 2xx（服务已就绪但返回错误）与线程中断不重试。</p>
	 *
	 * @param path 端点路径（默认根路径传 {@code ""}，如 jina 的 {@code POST /}）
	 * @param jsonBody JSON 请求体（如 {@code {"url": "https://example.com"}}）
	 */
	public String postJson(ContainerSpec spec, String path, String jsonBody) {
		Exception lastError = null;
		for (int attempt = 1; attempt <= CALL_RETRIES; attempt++) {
			// 每次重试 ensureRunning：touch 容器（防短 TTL/并发回收在重试窗口内回收容器）；若已被回收则重新拉起（幂等）
			if (containerManager != null) {
				containerManager.ensureRunning(spec);
			}
			try {
				return sendOnce(spec, path, jsonBody);
			}
			catch (IOException e) {
				lastError = e;
				// 连接不可达（ConnectException/UnknownHostException，含连接超时）不是启动窗口：
				// 容器未运行/端口错，重试也白费，快速失败
				if (isUnreachable(e)) {
					break;
				}
				log.warn("调用容器 {} REST 端点失败（第 {}/{} 次，将重试）：{}",
					spec.name(), attempt, CALL_RETRIES, e.getMessage());
				if (attempt < CALL_RETRIES) {
					sleep(RETRY_DELAY_MS);
				}
			}
		}
		throw new IllegalStateException("调用容器 " + spec.name() + " REST 端点失败（" + CALL_RETRIES
			+ " 次重试后仍失败）：" + (lastError == null ? "" : lastError.getMessage()), lastError);
	}

	/** 单次 POST 请求；连接层失败抛 {@link IOException}（由调用方重试），HTTP 非 2xx / 中断抛异常不重试。 */
	private String sendOnce(ContainerSpec spec, String path, String jsonBody) throws IOException {
		URI uri = URI.create(endpoint.restUrl(spec) + path);
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
			.build();
		final HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("调用容器 " + spec.name() + " REST 端点被中断：" + uri, e);
		}
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("容器 " + spec.name() + " 返回 HTTP " + response.statusCode()
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
