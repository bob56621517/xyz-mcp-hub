package io.xyz.xyz_mcp_hub.playwright;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.xyz.xyz_mcp_hub.playwright.internal.SharedChromium;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 浏览器会话租约注册表：维护 {@code sessionId → BrowserSessionHandle} 映射，支持创建、关闭、
 * TTL 自动回收与并发上限。
 *
 * <p>不同 sessionId 对应独立 {@code BrowserSessionHandle}（每会话独立 connectOverCDP 连接），
 * 可真正并发；同一 sessionId 由 handle 内部锁串行。后台 daemon 线程按
 * {@code playwright.session.scan-interval-seconds} 周期扫描，回收超过
 * {@code playwright.session.ttl-seconds} 未操作的会话，防无头浏览器泄漏。</p>
 */
@Component
public class WebSessionRegistry implements DisposableBean {

	private final SharedChromium sharedChromium;
	private final int maxSessions;
	private final long ttlNanos;
	private final long scanIntervalMillis;

	private final ConcurrentHashMap<String, BrowserSessionHandle> sessions = new ConcurrentHashMap<>();
	private final AtomicLong idSeq = new AtomicLong();

	private final Thread scanner;
	private volatile boolean running = true;

	public WebSessionRegistry(SharedChromium sharedChromium, PlaywrightProperties properties) {
		this.sharedChromium = sharedChromium;
		PlaywrightProperties.Session sessionProps = properties.getSession();
		this.maxSessions = sessionProps.getMax();
		this.ttlNanos = sessionProps.getTtlSeconds() * 1_000_000_000L;
		this.scanIntervalMillis = Math.max(1, sessionProps.getScanIntervalSeconds()) * 1000L;
		this.scanner = new Thread(this::scanLoop, "web-session-scanner");
		this.scanner.setDaemon(true);
		this.scanner.start();
	}

	private void scanLoop() {
		try {
			// 首轮先睡一个周期，避免应用刚启动就把新建会话误判过期
			Thread.sleep(scanIntervalMillis);
		}
		catch (InterruptedException e) {
			return;
		}
		while (running) {
			scanExpired();
			try {
				Thread.sleep(scanIntervalMillis);
			}
			catch (InterruptedException e) {
				return;
			}
		}
	}

	/** 创建一个新会话，返回其 sessionId；会话数已达上限时抛异常。 */
	public synchronized String create() {
		if (sessions.size() >= maxSessions) {
			throw new IllegalArgumentException("浏览器会话数已达上限 " + maxSessions
				+ "，请先 web_session(action=close) 关闭不再使用的会话");
		}
		BrowserSessionHandle handle = new BrowserSessionHandle(
				sharedChromium.cdpEndpoint(), sharedChromium.navigationTimeoutSeconds());
		String id = "ws-" + idSeq.incrementAndGet();
		sessions.put(id, handle);
		return id;
	}

	/** 按 sessionId 取句柄并刷新访问时间；会话不存在或已回收时抛异常。 */
	public BrowserSessionHandle handle(String sessionId) {
		BrowserSessionHandle handle = sessions.get(sessionId);
		if (handle == null) {
			throw new IllegalArgumentException("会话 " + sessionId
				+ " 不存在或已被关闭/回收，请先用 web_session(action=create) 创建会话");
		}
		handle.touch();
		return handle;
	}

	/** 关闭指定会话并释放其浏览器上下文；返回是否确有会话被关闭。 */
	public boolean close(String sessionId) {
		BrowserSessionHandle handle = sessions.remove(sessionId);
		if (handle == null) {
			return false;
		}
		handle.close();
		return true;
	}

	public int activeCount() {
		return sessions.size();
	}

	/** 回收所有超过 TTL 未操作的会话（供扫描线程与测试调用）。 */
	void scanExpired() {
		long now = System.nanoTime();
		sessions.forEach((id, handle) -> {
			if (now - handle.lastAccessNanos() > ttlNanos && sessions.remove(id, handle)) {
				try {
					handle.close();
				}
				catch (RuntimeException ignored) {
					// 会话关闭失败不影响其他会话回收
				}
			}
		});
	}

	@Override
	public void destroy() {
		running = false;
		scanner.interrupt();
		sessions.forEach((id, handle) -> {
			try {
				handle.close();
			}
			catch (RuntimeException ignored) {
				// 关闭单个会话失败不影响整体清理
			}
		});
		sessions.clear();
	}

}
