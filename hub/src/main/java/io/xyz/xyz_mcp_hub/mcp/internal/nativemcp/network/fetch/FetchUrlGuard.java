package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf.SsrUrlGuard.ResolvedTarget;

/**
 * SSRF 校验抽象：对 URL 做完整校验并锁定解析结果。
 *
 * <p>生产实现为 {@link SsrUrlGuard#resolveAndCheck(String)} 的方法引用；测试可注入
 * 放行本地测试服务的实现，以独立验证抓取管线（SSRF 拦截本身由 {@code SsrUrlGuardTest}
 * 与真实 guard 的用例覆盖）。</p>
 */
@FunctionalInterface
public interface FetchUrlGuard {

	/** 校验 URL 并返回锁定后的目标；被拦截时抛 {@link SsrUrlGuard.SsrGuardException}。 */
	ResolvedTarget resolveAndCheck(String url);
}
