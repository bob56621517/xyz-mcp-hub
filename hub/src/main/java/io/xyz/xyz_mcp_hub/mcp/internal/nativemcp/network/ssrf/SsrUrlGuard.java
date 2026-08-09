package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.ssrf;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * SSRF 防护组件（ADR-0010 决策 6）：fetch 端点快路径与浏览器路径统一复用的 URL 校验。
 *
 * <ul>
 *   <li>scheme 白名单：仅 http/https，拒绝 file://、ftp://、gopher:// 等</li>
 *   <li>内网/保留 IP 拦截：目标 IP 命中私网/回环/链路本地/保留段（IPv4 + IPv6，含 IPv4-mapped/
 *       IPv4-compatible、6to4/Teredo 内嵌 IPv4）即拒</li>
 *   <li>重定向逐跳校验：{@link #checkRedirect(String)} 对每跳 Location 重新走完整校验，
 *       防「公网 302 → 127.0.0.1」经典绕过</li>
 *   <li>DNS rebinding 防护：{@link #resolveAndCheck(String)} 只解析一次并锁定结果，
 *       调用方必须用 {@link ResolvedTarget#addresses()} 中的 IP 直连，不得对 host 二次解析</li>
 * </ul>
 *
 * <p>纯逻辑组件：不启动浏览器、不依赖真实网络；DNS 解析器可注入（默认 JDK 系统解析器），
 * 便于测试模拟解析结果并计数。</p>
 */
public final class SsrUrlGuard {

	/** 内网/保留网段表（CIDR）。IPv4 覆盖 RFC1918、回环、链路本地、测试网段、组播、保留；IPv6 覆盖回环、ULA、链路本地、组播及 6to4/Teredo 隧道。 */
	private static final List<Block> RESERVED_BLOCKS = List.of(
		block("0.0.0.0", 8),
		block("10.0.0.0", 8),
		block("100.64.0.0", 10),
		block("127.0.0.0", 8),
		block("169.254.0.0", 16),
		block("172.16.0.0", 12),
		block("192.0.0.0", 24),
		block("192.0.2.0", 24),
		block("192.168.0.0", 16),
		block("198.18.0.0", 15),
		block("198.51.100.0", 24),
		block("203.0.113.0", 24),
		block("224.0.0.0", 4),
		block("240.0.0.0", 4),
		block("::", 128),
		block("::1", 128),
		block("2001::", 32),
		block("2002::", 16),
		block("64:ff9b::", 96),
		block("100::", 64),
		block("2001:db8::", 32),
		block("2001:10::", 28),
		block("fc00::", 7),
		block("fe80::", 10),
		block("ff00::", 8)
	);

	/** 解析执行器：虚拟线程，静态共享避免每实例泄漏。 */
	private static final ExecutorService RESOLVER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	private final Function<String, InetAddress[]> resolver;
	private final Duration resolveTimeout;

	public SsrUrlGuard() {
		this(SsrUrlGuard::systemResolve, Duration.ofSeconds(5));
	}

	/** @param resolver 域名 → IP 列表的解析函数；测试可注入 fake 以模拟解析结果与调用计数 */
	public SsrUrlGuard(Function<String, InetAddress[]> resolver, Duration resolveTimeout) {
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.resolveTimeout = Objects.requireNonNull(resolveTimeout, "resolveTimeout");
	}

	/**
	 * 快速预检（不触发域名 DNS）：scheme 白名单 + host 非空；host 为 IP 字面量（含
	 * {@code 127.1}、整数形式、十六进制等疑似变体）时直接查内网/保留段并拦截。
	 * 真实域名不做解析，完整防护由 {@link #resolveAndCheck(String)} 承担。
	 */
	public void check(String url) {
		checkStatic(parse(url));
	}

	/**
	 * 完整校验：scheme/host 校验 + DNS 解析一次，解析出的每个 IP 必须为公网，否则拦截。
	 *
	 * <p>返回的 {@link ResolvedTarget} 携带解析并锁定后的 IP 列表——调用方建立连接必须用这些 IP
	 * （如 {@code InetSocketAddress} 直连），严禁对 {@code host} 发起第二次解析，否则 DNS rebinding
	 * 可在校验与连接之间把域名换成内网 IP。</p>
	 */
	public ResolvedTarget resolveAndCheck(String url) {
		URI uri = parse(url);
		InetAddress literal = checkStatic(uri);
		String host = uri.getHost();
		List<InetAddress> addresses = literal != null ? List.of(literal) : resolveAndVerify(uri, host);
		return new ResolvedTarget(uri, stripBrackets(host), effectivePort(uri), addresses);
	}

	/**
	 * 重定向逐跳校验：对下一跳 Location 重新走完整校验。调用方在重定向链的每一跳都必须调用，
	 * 防「公网 URL 302 → 127.0.0.1」绕过。Location 须为绝对 URL（相对 Location 由调用方先解析）。
	 */
	public ResolvedTarget checkRedirect(String location) {
		return resolveAndCheck(location);
	}

	/**
	 * scheme/host 静态校验 + IP 字面量（含疑似变体）内网拦截；真实域名不解析，返回 null。
	 */
	private static InetAddress checkStatic(URI uri) {
		checkScheme(uri);
		String host = requireHost(uri);
		InetAddress literal = resolveIpLiteral(host);
		if (literal == null) {
			literal = resolveSuspiciousLiteral(host);
		}
		if (literal != null) {
			rejectIfPrivate(literal, uri);
		}
		return literal;
	}

	private List<InetAddress> resolveAndVerify(URI uri, String host) {
		List<InetAddress> addresses = resolveOnce(host);
		for (InetAddress address : addresses) {
			rejectIfPrivate(address, uri);
		}
		return addresses;
	}

	private List<InetAddress> resolveOnce(String host) {
		CompletableFuture<InetAddress[]> future = CompletableFuture.supplyAsync(
			() -> resolver.apply(host), RESOLVER_EXECUTOR);
		try {
			InetAddress[] resolved = future.get(resolveTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (resolved == null || resolved.length == 0) {
				throw new SsrGuardException("主机解析无结果：" + host);
			}
			return Arrays.asList(resolved);
		}
		catch (TimeoutException e) {
			throw new SsrGuardException("主机解析超时（" + resolveTimeout + "）：" + host, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SsrGuardException("主机解析被中断：" + host, e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof SsrGuardException guardException) {
				throw guardException;
			}
			throw new SsrGuardException("主机解析失败：" + host, cause != null ? cause : e);
		}
	}

	private static void rejectIfPrivate(InetAddress address, URI uri) {
		InetAddress normalized = normalizeMapped(address);
		if (isPrivateOrReserved(normalized)) {
			throw new SsrGuardException("目标 IP 落在内网/保留段，已拦截：" + uri + " → " + address.getHostAddress());
		}
	}

	/** IPv4-mapped（::ffff:a.b.c.d）与 IPv4-compatible（::a.b.c.d）统一归一为 IPv4 再判断。 */
	private static InetAddress normalizeMapped(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length != 16) {
			return address;
		}
		if (isZero(bytes, 0, 10) && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
			return toIpv4(bytes, 12, address);
		}
		if (isZero(bytes, 0, 12)) {
			return toIpv4(bytes, 12, address);
		}
		return address;
	}

	private static boolean isZero(byte[] bytes, int from, int to) {
		for (int i = from; i < to; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return true;
	}

	private static InetAddress toIpv4(byte[] bytes, int offset, InetAddress fallback) {
		try {
			return InetAddress.getByAddress(Arrays.copyOfRange(bytes, offset, offset + 4));
		}
		catch (UnknownHostException e) {
			return fallback;
		}
	}

	private static boolean isPrivateOrReserved(InetAddress address) {
		byte[] bytes = address.getAddress();
		for (Block block : RESERVED_BLOCKS) {
			if (bytes.length == block.network().length && matches(bytes, block.network(), block.prefixLen())) {
				return true;
			}
		}
		return false;
	}

	private static boolean matches(byte[] address, byte[] network, int prefixLen) {
		int fullBytes = prefixLen / 8;
		int remBits = prefixLen % 8;
		for (int i = 0; i < fullBytes; i++) {
			if (address[i] != network[i]) {
				return false;
			}
		}
		if (remBits > 0) {
			int mask = 0xff << (8 - remBits);
			if ((address[fullBytes] & mask) != (network[fullBytes] & mask)) {
				return false;
			}
		}
		return true;
	}

	/** 解析严格 IP 字面量（4 段十进制 IPv4 / IPv6）；非字面量返回 null。IP 字面量不会触发 DNS。 */
	private static InetAddress resolveIpLiteral(String host) {
		String candidate = stripBrackets(host);
		if (candidate.indexOf(':') >= 0) {
			try {
				return InetAddress.getByName(candidate);
			}
			catch (UnknownHostException e) {
				throw new SsrGuardException("非法 IPv6 地址：" + host, e);
			}
		}
		String digits = stripTrailingDots(candidate);
		if (hasAmbiguousLeadingZero(digits)) {
			throw new SsrGuardException("疑似前导零 IPv4 字面量，存在解析歧义，拒绝：" + host);
		}
		int[] octets = parseIpv4(digits);
		if (octets == null) {
			return null;
		}
		try {
			return InetAddress.getByAddress(new byte[] {
				(byte) octets[0], (byte) octets[1], (byte) octets[2], (byte) octets[3] });
		}
		catch (UnknownHostException e) {
			throw new SsrGuardException("非法 IPv4 地址：" + host, e);
		}
	}

	/** 严格 4 段十进制 IPv4 字面量解析；非 IPv4 字面量返回 null。 */
	private static int[] parseIpv4(String candidate) {
		if (candidate.isEmpty() || candidate.length() > 15
			|| !candidate.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) {
			return null;
		}
		String[] parts = candidate.split("\\.", -1);
		if (parts.length != 4) {
			return null;
		}
		int[] octets = new int[4];
		for (int i = 0; i < 4; i++) {
			String part = parts[i];
			if (part.isEmpty() || part.length() > 3) {
				return null;
			}
			int value = Integer.parseInt(part);
			if (value > 255) {
				return null;
			}
			octets[i] = value;
		}
		return octets;
	}

	/** 点分数字中存在「以 0 开头且不止一位」的段（如 010、0177）：八进制/十进制解析歧义，必须拒绝。 */
	private static boolean hasAmbiguousLeadingZero(String candidate) {
		String[] parts = candidate.split("\\.", -1);
		for (String part : parts) {
			if (part.length() > 1 && part.charAt(0) == '0'
				&& part.chars().allMatch(c -> c >= '0' && c <= '9')) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 对「疑似 IP 字形但非严格字面量」的 host（{@code 127.1}、整数形式、十六进制段等）用 JDK 解析，
	 * 校验内网并拦截；不触发真实域名 DNS。解析失败视为歧义，拒绝。
	 */
	private static InetAddress resolveSuspiciousLiteral(String host) {
		String candidate = stripTrailingDots(stripBrackets(host));
		if (!isIpLiteralCandidate(candidate)) {
			return null;
		}
		try {
			return InetAddress.getByName(candidate);
		}
		catch (UnknownHostException | IllegalArgumentException e) {
			throw new SsrGuardException("疑似 IP 字面量无法解析，拒绝：" + host);
		}
	}

	/** 判定 host 是否「IP 字形」：IPv6、纯整数、或 ≤4 段的点分十进制/十六进制（含 0x 前缀）。 */
	private static boolean isIpLiteralCandidate(String host) {
		if (host.isEmpty() || host.length() > 64) {
			return false;
		}
		if (host.indexOf(':') >= 0) {
			return true;
		}
		if (host.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return true;
		}
		String[] parts = host.split("\\.", -1);
		if (parts.length < 2 || parts.length > 4) {
			return false;
		}
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 4) {
				return false;
			}
			for (int i = 0; i < part.length(); i++) {
				char c = part.charAt(i);
				if (!isHexDigit(c) && c != 'x' && c != 'X') {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	private static String stripBrackets(String host) {
		if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
			return host.substring(1, host.length() - 1);
		}
		return host;
	}

	private static String stripTrailingDots(String host) {
		int end = host.length();
		while (end > 0 && host.charAt(end - 1) == '.') {
			end--;
		}
		return host.substring(0, end);
	}

	private static URI parse(String url) {
		try {
			return new URI(url);
		}
		catch (URISyntaxException e) {
			throw new SsrGuardException("非法 URL：" + url, e);
		}
	}

	private static void checkScheme(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
			throw new SsrGuardException("scheme 不在白名单（仅 http/https）：" + uri);
		}
	}

	private static String requireHost(URI uri) {
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new SsrGuardException("URL 缺少主机名：" + uri);
		}
		return host;
	}

	private static int effectivePort(URI uri) {
		int port = uri.getPort();
		if (port > 0) {
			return port;
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static InetAddress[] systemResolve(String host) {
		try {
			return InetAddress.getAllByName(host);
		}
		catch (UnknownHostException e) {
			throw new SsrGuardException("主机解析失败：" + host, e);
		}
	}

	/** 校验通过并锁定后的目标：调用方用 {@link #addresses()} 中的 IP 直连，严禁二次解析 host。 */
	public record ResolvedTarget(URI uri, String host, int port, List<InetAddress> addresses) {

		public ResolvedTarget {
			addresses = List.copyOf(addresses);
		}

		/** 首个公网 IP，供建连（如 {@code InetSocketAddress}）。 */
		public InetAddress firstAddress() {
			return addresses.get(0);
		}
	}

	private record Block(byte[] network, int prefixLen) {
	}

	private static Block block(String cidr, int prefixLen) {
		try {
			return new Block(InetAddress.getByName(cidr).getAddress(), prefixLen);
		}
		catch (UnknownHostException e) {
			throw new ExceptionInInitializerError("非法保留网段：" + cidr);
		}
	}

	/** URL 被 SSRF 防护拦截时抛出。 */
	public static class SsrGuardException extends IllegalArgumentException {

		public SsrGuardException(String message) {
			super(message);
		}

		public SsrGuardException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
