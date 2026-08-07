package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * fetch 快路径的 HTTP 客户端：Apache HttpClient 5 封装，实现 SSRF 锁定直连。
 *
 * <p>核心约束来自 {@link io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard}
 * 的 DNS rebinding 防护：建连必须使用 {@code resolveAndCheck} 锁定的公网 IP，严禁对 host
 * 二次解析。故 {@link #lock(String, InetAddress)} 将校验通过的 host→IP 写入并发表，
 * 自定义 {@link DnsResolver} 只返回表内 IP，其余一律抛 {@link UnknownHostException} 拒绝建连。</p>
 *
 * <p>自动重定向被禁用（{@code disableRedirectHandling}）：3xx 由 {@link FetchService} 逐跳
 * 重新走完整 SSRF 校验，防「公网 302 → 127.0.0.1」绕过。</p>
 */
@Component
public final class FetchHttpClient implements AutoCloseable, DisposableBean {

	/** 单次响应体最大字节数，超出截断，避免恶意/超大文件撑爆内存。 */
	private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

	private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)");

	private final CloseableHttpClient client;
	private final ConcurrentMap<String, InetAddress> lockedHosts = new ConcurrentHashMap<>();

	public FetchHttpClient() {
		RequestConfig requestConfig = RequestConfig.custom()
			.setConnectTimeout(Timeout.ofSeconds(10))
			.setResponseTimeout(Timeout.ofSeconds(30))
			.setConnectionRequestTimeout(Timeout.ofSeconds(10))
			.build();
		PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
			.setDnsResolver(new DnsResolver() {
				@Override
				public InetAddress[] resolve(String host) throws UnknownHostException {
					return resolveLocked(host);
				}

				@Override
				public String resolveCanonicalHostname(String host) {
					return host;
				}
			})
			.build();
		this.client = HttpClientBuilder.create()
			.setConnectionManager(connectionManager)
			.setDefaultRequestConfig(requestConfig)
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.build();
	}

	/** 记录「校验通过的 host → 锁定公网 IP」，供 {@link DnsResolver} 建连。 */
	public void lock(String host, InetAddress address) {
		lockedHosts.put(host, address);
	}

	/** 移除锁定；请求结束必须调用，避免脏数据残留。 */
	public void unlock(String host) {
		lockedHosts.remove(host);
	}

	private InetAddress[] resolveLocked(String host) throws UnknownHostException {
		InetAddress locked = lockedHosts.get(host);
		if (locked == null) {
			throw new UnknownHostException("未经 SSRF 校验的主机，拒绝建连：" + host);
		}
		return new InetAddress[] { locked };
	}

	@Override
	public void close() {
		try {
			client.close();
		}
		catch (IOException ignored) {
			// 连接池关闭失败无可恢复动作
		}
	}

	@Override
	public void destroy() {
		close();
	}

	/** 执行 GET，返回状态、重定向 Location、Content-Type、编码与（截断后的）body。 */
	public FetchResponse execute(URI uri) throws IOException {
		HttpGet request = new HttpGet(uri);
		request.setHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; xyz-mcp-hub-fetch/1.0)");
		request.setHeader(HttpHeaders.ACCEPT, "text/html,application/pdf,application/xhtml+xml,text/plain,*/*;q=0.8");

		try (CloseableHttpResponse response = client.execute(request)) {
			int status = response.getCode();
			String location = firstHeader(response, "Location");
			String contentType = firstHeader(response, HttpHeaders.CONTENT_TYPE);
			Charset charset = parseCharset(contentType);

			byte[] body = new byte[0];
			if (response.getEntity() != null) {
				body = readBody(response.getEntity().getContent());
			}
			return new FetchResponse(status, location, contentType, charset, body);
		}
	}

	private static String firstHeader(CloseableHttpResponse response, String name) {
		var header = response.getFirstHeader(name);
		return header == null ? null : header.getValue();
	}

	private static Charset parseCharset(String contentType) {
		if (contentType == null) {
			return null;
		}
		Matcher matcher = CHARSET_PATTERN.matcher(contentType);
		if (matcher.find()) {
			try {
				return Charset.forName(matcher.group(1));
			}
			catch (RuntimeException ignored) {
				// 非法 charset 名 → 回退按类型默认
			}
		}
		return null;
	}

	private static byte[] readBody(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		byte[] buf = new byte[8192];
		int total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			if (total + n > MAX_BODY_BYTES) {
				out.write(buf, 0, MAX_BODY_BYTES - total);
				break;
			}
			out.write(buf, 0, n);
			total += n;
		}
		return out.toByteArray();
	}

	/** 一次成功响应的快照。 */
	public record FetchResponse(
			int status,
			String location,
			String contentType,
			Charset charset,
			byte[] body) {

		public boolean isRedirect() {
			return status >= 300 && status < 400;
		}

		public String mediaType() {
			if (contentType == null) {
				return null;
			}
			int semicolon = contentType.indexOf(';');
			String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
			return type.trim().toLowerCase(Locale.ROOT);
		}

		/** body 文本；无 charset 声明时按 UTF-8 解码。 */
		public String bodyText() {
			Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
			return new String(body, cs);
		}
	}
}
