package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.microsoft.playwright.Playwright;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 共享 chromium 进程管理器：懒启动**一个**带 CDP 调试端口的无头 chromium 进程，供多个会话
 * 通过 {@code connectOverCDP} 独立连接。多会话共享同一浏览器进程树（省内存），但每会话持有
 * 独立连接与对象表（线程安全），从而支持跨会话真并发——规避了单 Playwright 实例跨线程
 * 操作报 {@code Object doesn't exist} 的底层限制。
 *
 * <p>进程通过 {@link ProcessBuilder} 拉起（Playwright Java 无 {@code launchServer}），端口经
 * {@code <user-data-dir>/DevToolsActivePort} 探测；应用关闭时随 {@link #destroy()} 终止进程
 * 并清理临时用户数据目录。</p>
 */
@Component
public class SharedChromium implements DisposableBean {

	private static final long PORT_WAIT_TIMEOUT_MS = 20_000;

	private final boolean headless;
	private final double navigationTimeoutSeconds;

	private volatile String cdpEndpoint;
	private volatile Process process;
	private volatile Path userDataDir;

	public SharedChromium(
			@Value("${playwright.headless:true}") boolean headless,
			@Value("${playwright.navigation-timeout-seconds:30}") double navigationTimeoutSeconds) {
		this.headless = headless;
		this.navigationTimeoutSeconds = navigationTimeoutSeconds;
	}

	private synchronized void start() {
		if (cdpEndpoint != null) {
			return;
		}
		Path dataDir = null;
		Process started = null;
		try {
			String executable;
			try (Playwright pw = Playwright.create()) {
				executable = pw.chromium().executablePath();
			}
			dataDir = Files.createTempDirectory("xyz-mcp-hub-chromium");
			List<String> command = new ArrayList<>();
			command.add(executable);
			if (headless) {
				command.add("--headless=new");
			}
			command.add("--remote-debugging-port=0");
			command.add("--remote-allow-origins=*");
			command.add("--disable-back-forward-cache");
			command.add("--user-data-dir=" + dataDir);
			command.add("--no-first-run");
			command.add("--no-default-browser-check");
			command.add("--disable-gpu");
			command.add("about:blank");
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			started = builder.start();
			int port = waitForCdpPort(dataDir);
			this.process = started;
			this.userDataDir = dataDir;
			this.cdpEndpoint = "http://127.0.0.1:" + port;
		}
		catch (IOException e) {
			cleanup(started, dataDir);
			throw new IllegalStateException("启动共享 chromium 失败", e);
		}
		catch (RuntimeException e) {
			// 端口未就绪等启动失败：销毁已拉起的进程并清理临时目录
			cleanup(started, dataDir);
			throw e;
		}
	}

	private static void cleanup(Process process, Path dataDir) {
		if (process != null) {
			process.destroyForcibly();
		}
		if (dataDir != null) {
			deleteDir(dataDir);
		}
	}

	/** CDP 调试端口；首次调用懒启动共享 chromium 进程。 */
	public String cdpEndpoint() {
		start();
		return cdpEndpoint;
	}

	public double navigationTimeoutSeconds() {
		return navigationTimeoutSeconds;
	}

	private static int waitForCdpPort(Path userDataDir) {
		Path portFile = userDataDir.resolve("DevToolsActivePort");
		long deadline = System.currentTimeMillis() + PORT_WAIT_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			try {
				if (Files.exists(portFile)) {
					String firstLine = Files.readString(portFile).split("\\r?\\n", 2)[0].strip();
					if (!firstLine.isEmpty()) {
						return Integer.parseInt(firstLine);
					}
				}
			}
			catch (IOException | NumberFormatException ignored) {
				// 文件尚未写全，继续轮询
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("等待 chromium CDP 端口被中断", e);
			}
		}
		throw new IllegalStateException("chromium CDP 端口在 " + PORT_WAIT_TIMEOUT_MS + "ms 内未就绪");
	}

	@Override
	public void destroy() {
		Process p = process;
		if (p != null) {
			p.destroy();
			try {
				if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
					p.destroyForcibly();
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				p.destroyForcibly();
			}
		}
		Path dir = userDataDir;
		if (dir != null) {
			deleteDir(dir);
		}
	}

	private static void deleteDir(Path dir) {
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
					// 个别文件被进程占用时忽略，临时目录残留无碍
				}
			});
		}
		catch (IOException ignored) {
			// 目录不存在时忽略
		}
	}

}
