package io.xyz.xyz_mcp_hub.playwright;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * playwright 引擎配置（前缀 {@code playwright}）：共享 chromium 进程与会话租约的可配项
 * 收敛于此，替代原先散落的 {@code @Value} 注入。
 */
@ConfigurationProperties(prefix = "playwright")
public class PlaywrightProperties {

	/** 是否无头启动共享 chromium（默认 true）。 */
	private boolean headless = true;

	/** 单次导航超时（秒，默认 30）。 */
	private double navigationTimeoutSeconds = 30;

	/** 会话租约配置。 */
	private Session session = new Session();

	public boolean isHeadless() {
		return headless;
	}

	public void setHeadless(boolean headless) {
		this.headless = headless;
	}

	public double getNavigationTimeoutSeconds() {
		return navigationTimeoutSeconds;
	}

	public void setNavigationTimeoutSeconds(double navigationTimeoutSeconds) {
		this.navigationTimeoutSeconds = navigationTimeoutSeconds;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	/** 浏览器会话租约配置。 */
	public static class Session {

		/** 并发会话上限（默认 8）。 */
		private int max = 8;

		/** 会话无操作自动回收时长（秒，默认 300）。 */
		private long ttlSeconds = 300;

		/** 后台回收扫描周期（秒，默认 60）。 */
		private long scanIntervalSeconds = 60;

		public int getMax() {
			return max;
		}

		public void setMax(int max) {
			this.max = max;
		}

		public long getTtlSeconds() {
			return ttlSeconds;
		}

		public void setTtlSeconds(long ttlSeconds) {
			this.ttlSeconds = ttlSeconds;
		}

		public long getScanIntervalSeconds() {
			return scanIntervalSeconds;
		}

		public void setScanIntervalSeconds(long scanIntervalSeconds) {
			this.scanIntervalSeconds = scanIntervalSeconds;
		}
	}
}
