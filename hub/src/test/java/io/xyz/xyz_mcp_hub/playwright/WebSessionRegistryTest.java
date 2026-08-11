package io.xyz.xyz_mcp_hub.playwright;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 浏览器会话租约注册表能力层单测（#54，S1）：验证 {@link WebSessionRegistry} 的创建（ws-N 递增）、
 * 并发上限强制、handle 取句柄 + 触碰刷新、close 释放、TTL 闲置回收与 destroy 全清。经包私有
 * handle 工厂 seam 注入 mock {@link BrowserSessionHandle}——**不触 chromium、不启 Spring、不触网**。
 *
 * <p>原 {@code McpPlaywrightSessionMaxTest}/{@code McpPlaywrightSessionTtlTest}（MCP 层，需真实
 * chromium）删后由本类补齐上限/TTL 语义覆盖（#54 三层收敛）。后台扫描线程以超大扫描周期
 * （3600s）启动避免干扰，TTL 回收直接调包私有 {@code scanExpired()} 确定性断言。</p>
 */
class WebSessionRegistryTest {

	private final List<BrowserSessionHandle> handles = new ArrayList<>();
	private WebSessionRegistry registry;

	private BrowserSessionHandle newHandle() {
		BrowserSessionHandle handle = mock(BrowserSessionHandle.class);
		handles.add(handle);
		return handle;
	}

	private WebSessionRegistry registry(int max, long ttlSeconds) {
		PlaywrightProperties props = new PlaywrightProperties();
		props.getSession().setMax(max);
		props.getSession().setTtlSeconds(ttlSeconds);
		props.getSession().setScanIntervalSeconds(3600); // 后台扫描不干扰，TTL 断言走 scanExpired() 直调
		registry = new WebSessionRegistry(props, this::newHandle);
		return registry;
	}

	@AfterEach
	void tearDown() {
		if (registry != null) {
			registry.destroy();
		}
	}

	// ---- 创建 / 上限 ----

	@Test
	void createReturnsIncrementalIds() {
		WebSessionRegistry r = registry(8, 300);
		assertThat(r.create()).isEqualTo("ws-1");
		assertThat(r.create()).isEqualTo("ws-2");
		assertThat(r.activeCount()).isEqualTo(2);
	}

	@Test
	void createEnforcesMax() {
		WebSessionRegistry r = registry(2, 300);
		r.create();
		r.create();
		assertThatThrownBy(r::create)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("已达上限");
		assertThat(r.activeCount()).isEqualTo(2);
	}

	// ---- handle：取句柄 + 触碰刷新 / 缺失报错 ----

	@Test
	void handleReturnsHandleAndTouches() {
		WebSessionRegistry r = registry(8, 300);
		String id = r.create();
		BrowserSessionHandle h = handles.get(0);
		assertThat(r.handle(id)).isSameAs(h);
		verify(h).touch();
	}

	@Test
	void handleMissingSessionThrows() {
		WebSessionRegistry r = registry(8, 300);
		assertThatThrownBy(() -> r.handle("ws-dead"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不存在或已被关闭/回收");
	}

	// ---- close：释放 / 缺失返回 false ----

	@Test
	void closeReturnsTrueAndReleasesHandle() {
		WebSessionRegistry r = registry(8, 300);
		String id = r.create();
		BrowserSessionHandle h = handles.get(0);
		assertThat(r.close(id)).isTrue();
		verify(h).close();
		assertThat(r.activeCount()).isZero();
	}

	@Test
	void closeMissingReturnsFalse() {
		WebSessionRegistry r = registry(8, 300);
		assertThat(r.close("ws-dead")).isFalse();
	}

	// ---- TTL 闲置回收（scanExpired 直调，确定性断言） ----

	@Test
	void scanExpiredReclaimsIdleSessions() {
		WebSessionRegistry r = registry(8, 10); // ttl 10s
		r.create();
		BrowserSessionHandle h = handles.get(0);
		// 模拟闲置：句柄最后访问在 100s 前（远超 ttl）
		when(h.lastAccessNanos()).thenReturn(System.nanoTime() - 100_000_000_000L);

		r.scanExpired();

		assertThat(r.activeCount()).isZero();
		verify(h).close();
	}

	@Test
	void scanExpiredKeepsRecentlyTouchedSessions() {
		WebSessionRegistry r = registry(8, 10); // ttl 10s
		r.create();
		BrowserSessionHandle h = handles.get(0);
		// 最近活动：最后访问在 1s 前（ttl 内），不应回收
		when(h.lastAccessNanos()).thenReturn(System.nanoTime() - 1_000_000_000L);

		r.scanExpired();

		assertThat(r.activeCount()).isEqualTo(1);
		verify(h, never()).close();
	}

	// ---- destroy：全清 + 关闭所有句柄 ----

	@Test
	void destroyClosesAllHandlesAndClears() {
		WebSessionRegistry r = registry(8, 300);
		r.create();
		r.create();
		r.destroy();
		assertThat(handles).allSatisfy(h -> verify(h).close());
		assertThat(r.activeCount()).isZero();
	}
}
