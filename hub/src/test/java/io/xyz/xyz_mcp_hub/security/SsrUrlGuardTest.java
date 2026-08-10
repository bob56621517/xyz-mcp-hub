package io.xyz.xyz_mcp_hub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.ResolvedTarget;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.SsrGuardException;
import org.junit.jupiter.api.Test;

/**
 * {@link SsrUrlGuard} 单测：scheme 白名单、内网/保留 IP 拦截、重定向逐跳校验、DNS rebinding 锁定。
 * 纯逻辑测试：一律使用 IP 字面量与注入的 fake resolver，不依赖真实 DNS / 外部网络。
 */
class SsrUrlGuardTest {

	private final SsrUrlGuard guard = new SsrUrlGuard();

	// ---------- scheme 白名单 ----------

	@Test
	void httpSchemeAllowed() {
		assertThatCode(() -> guard.check("http://example.com/path")).doesNotThrowAnyException();
	}

	@Test
	void httpsSchemeAllowed() {
		assertThatCode(() -> guard.check("https://example.com/path")).doesNotThrowAnyException();
	}

	@Test
	void fileSchemeRejected() {
		assertThatThrownBy(() -> guard.check("file:///etc/passwd")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ftpSchemeRejected() {
		assertThatThrownBy(() -> guard.check("ftp://example.com/file")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void gopherSchemeRejected() {
		assertThatThrownBy(() -> guard.check("gopher://example.com")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void javascriptSchemeRejected() {
		assertThatThrownBy(() -> guard.check("javascript:alert(1)")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void dataSchemeRejected() {
		assertThatThrownBy(() -> guard.check("data:text/html,<h1>x</h1>")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void missingSchemeRejected() {
		assertThatThrownBy(() -> guard.check("//example.com/path")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void missingHostRejected() {
		assertThatThrownBy(() -> guard.check("http:/path")).isInstanceOf(SsrGuardException.class);
	}

	// ---------- 内网/保留 IP 拦截（IP 字面量，不触发 DNS） ----------

	@Test
	void loopbackIpv4Rejected() {
		assertThatThrownBy(() -> guard.check("http://127.0.0.1/")).isInstanceOf(SsrGuardException.class);
		assertThatThrownBy(() -> guard.check("http://127.1.2.3:8080/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void rfc1918Rejected() {
		assertThatThrownBy(() -> guard.check("http://10.0.0.1/")).isInstanceOf(SsrGuardException.class);
		assertThatThrownBy(() -> guard.check("http://172.16.0.1/")).isInstanceOf(SsrGuardException.class);
		assertThatThrownBy(() -> guard.check("http://172.31.255.255/")).isInstanceOf(SsrGuardException.class);
		assertThatThrownBy(() -> guard.check("http://192.168.1.1/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void linkLocalRejected() {
		assertThatThrownBy(() -> guard.check("http://169.254.169.254/latest/meta-data/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void unspecifiedIpv4Rejected() {
		assertThatThrownBy(() -> guard.check("http://0.0.0.0/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void publicIpv4Allowed() {
		assertThatCode(() -> guard.check("http://93.184.216.34/")).doesNotThrowAnyException();
		assertThatCode(() -> guard.check("http://1.1.1.1/")).doesNotThrowAnyException();
	}

	// ---------- 非规范 IPv4 变体（快路径 check 也要拦） ----------

	@Test
	void shortDottedIpv4Rejected() {
		assertThatThrownBy(() -> guard.check("http://127.1/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void integerIpv4Rejected() {
		assertThatThrownBy(() -> guard.check("http://2130706433/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void hexadecimalIpv4Rejected() {
		assertThatThrownBy(() -> guard.check("http://0x7f.0.0.1/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv4WithTrailingDotRejected() {
		assertThatThrownBy(() -> guard.check("http://127.0.0.1./")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv4LeadingZeroRejected() {
		assertThatThrownBy(() -> guard.check("http://010.0.0.1/")).isInstanceOf(SsrGuardException.class);
		assertThatThrownBy(() -> guard.check("http://0177.0.0.1/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ambiguousIpLiteralResolveFailureRejected() {
		// 疑似 IP 字形但 JDK 无法解析 → 拒绝而非放行
		assertThatThrownBy(() -> guard.check("http://999.999.999.999/")).isInstanceOf(SsrGuardException.class);
	}

	// ---------- IPv6 字面量 ----------

	@Test
	void loopbackIpv6Rejected() {
		assertThatThrownBy(() -> guard.check("http://[::1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv6PrivateRejected() {
		assertThatThrownBy(() -> guard.check("http://[fc00::1]/")).isInstanceOf(SsrGuardException.class);
		assertThatThrownBy(() -> guard.check("http://[fd00::1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv6LinkLocalRejected() {
		assertThatThrownBy(() -> guard.check("http://[fe80::1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv6MulticastRejected() {
		assertThatThrownBy(() -> guard.check("http://[ff02::1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv6DocumentationRejected() {
		assertThatThrownBy(() -> guard.check("http://[2001:db8::1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv4MappedLoopbackRejected() {
		assertThatThrownBy(() -> guard.check("http://[::ffff:127.0.0.1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv4MappedPrivateRejected() {
		assertThatThrownBy(() -> guard.check("http://[::ffff:10.0.0.1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void ipv4CompatiblePrivateRejected() {
		assertThatThrownBy(() -> guard.check("http://[::10.0.0.1]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void sixToFourTunnelRejected() {
		// 6to4 隧道：2002:7f00:1:: 内嵌 IPv4 127.0.0.1
		assertThatThrownBy(() -> guard.check("http://[2002:7f00:1::]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void teredoTunnelRejected() {
		assertThatThrownBy(() -> guard.check("http://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void publicIpv6Allowed() {
		assertThatCode(() -> guard.check("http://[2606:2800:220:1:248:1893:25c8:1946]/")).doesNotThrowAnyException();
	}

	// ---------- 域名解析拦截（fake resolver，无网络） ----------

	@Test
	void localhostRejectedViaSystemResolver() {
		assertThatThrownBy(() -> guard.resolveAndCheck("http://localhost/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void domainResolvedToPrivateRejected() {
		SsrUrlGuard guard = withResolver(new StubResolver().map("internal.test", "10.0.0.5"));
		assertThatThrownBy(() -> guard.resolveAndCheck("http://internal.test/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void domainResolvedToLoopbackRejected() {
		SsrUrlGuard guard = withResolver(new StubResolver().map("app.test", "127.0.0.1"));
		assertThatThrownBy(() -> guard.resolveAndCheck("http://app.test/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void domainResolvedToPublicAllowedAndLocked() {
		SsrUrlGuard guard = withResolver(new StubResolver().map("example.com", "93.184.216.34"));
		ResolvedTarget target = guard.resolveAndCheck("http://example.com/path");
		assertThat(target.host()).isEqualTo("example.com");
		assertThat(target.port()).isEqualTo(80);
		assertThat(target.addresses()).containsExactly(ip("93.184.216.34"));
		assertThat(target.firstAddress()).isEqualTo(ip("93.184.216.34"));
	}

	@Test
	void httpsPortAndExplicitPortResolved() {
		SsrUrlGuard guard = withResolver(new StubResolver().map("example.com", "93.184.216.34"));
		assertThat(guard.resolveAndCheck("https://example.com/").port()).isEqualTo(443);
		assertThat(guard.resolveAndCheck("https://example.com:8443/").port()).isEqualTo(8443);
	}

	@Test
	void invalidPortFallsBackToDefault() {
		SsrUrlGuard guard = withResolver(new StubResolver().map("example.com", "93.184.216.34"));
		assertThat(guard.resolveAndCheck("http://example.com:0/").port()).isEqualTo(80);
	}

	// ---------- DNS rebinding 锁定（解析一次） ----------

	@Test
	void dnsResolvedExactlyOnce() {
		StubResolver resolver = new StubResolver().map("example.com", "93.184.216.34");
		SsrUrlGuard guard = withResolver(resolver);
		guard.resolveAndCheck("http://example.com/");
		assertThat(resolver.invocations).as("DNS 必须只解析一次并锁定结果").isEqualTo(1);
	}

	@Test
	void dnsRebindingSecondConnectionRejected() {
		// 攻击者视角：第一次连接返回公网 IP 通过；第二次发起新连接时域名改指内网 IP，必须被拦
		StubResolver resolver = new StubResolver().map("shop.test", "93.184.216.34");
		SsrUrlGuard guard = withResolver(resolver);
		assertThatCode(() -> guard.resolveAndCheck("http://shop.test/")).doesNotThrowAnyException();

		resolver.map("shop.test", "10.0.0.1");
		assertThatThrownBy(() -> guard.resolveAndCheck("http://shop.test/")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void lockedAddressesAreTheResolvedOnes() {
		StubResolver resolver = new StubResolver()
			.map("example.com", "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946");
		ResolvedTarget target = withResolver(resolver).resolveAndCheck("http://example.com/");
		assertThat(target.addresses()).hasSize(2)
			.contains(ip("93.184.216.34"))
			.contains(ip("2606:2800:220:1:248:1893:25c8:1946"));
	}

	// ---------- 重定向逐跳校验 ----------

	@Test
	void redirectToPrivateBlocked() {
		assertThatThrownBy(() -> guard.checkRedirect("http://127.0.0.1/admin")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void redirectToPrivateByHostnameBlocked() {
		SsrUrlGuard guard = withResolver(new StubResolver().map("cdn.test", "10.0.0.2"));
		assertThatThrownBy(() -> guard.checkRedirect("http://cdn.test/evil")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void redirectChainEveryHopValidated() {
		// 两跳重定向：公网入口通过 → 中继公网通过 → 最后一跳改指内网被拦
		StubResolver resolver = new StubResolver()
			.map("public.test", "93.184.216.34")
			.map("relay.test", "1.1.1.1")
			.map("evil.test", "127.0.0.1");
		SsrUrlGuard guard = withResolver(resolver);

		assertThatCode(() -> guard.resolveAndCheck("http://public.test/start")).doesNotThrowAnyException();
		assertThatCode(() -> guard.checkRedirect("http://relay.test/hop")).doesNotThrowAnyException();
		assertThatThrownBy(() -> guard.checkRedirect("http://evil.test/steal")).isInstanceOf(SsrGuardException.class);
	}

	@Test
	void redirectSchemeNotWhitelistedRejected() {
		assertThatThrownBy(() -> guard.checkRedirect("file:///etc/shadow")).isInstanceOf(SsrGuardException.class);
	}

	// ---------- 工具 ----------

	private static SsrUrlGuard withResolver(StubResolver resolver) {
		return new SsrUrlGuard(resolver, Duration.ofSeconds(5));
	}

	private static InetAddress ip(String literal) {
		try {
			return InetAddress.getByName(literal);
		}
		catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 可注入的 fake 解析器：按 host 返回预设 IP，并统计调用次数。 */
	private static final class StubResolver implements Function<String, InetAddress[]> {

		private final Map<String, List<InetAddress>> mapping = new HashMap<>();
		int invocations;

		StubResolver map(String host, String... ipLiterals) {
			mapping.put(host, Arrays.stream(ipLiterals).map(SsrUrlGuardTest::ip).toList());
			return this;
		}

		@Override
		public InetAddress[] apply(String host) {
			invocations++;
			List<InetAddress> ips = mapping.get(host);
			return ips == null ? new InetAddress[0] : ips.toArray(InetAddress[]::new);
		}
	}
}
