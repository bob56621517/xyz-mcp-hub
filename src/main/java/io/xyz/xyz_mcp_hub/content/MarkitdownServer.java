package io.xyz.xyz_mcp_hub.content;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * markitdown 本地子进程生命周期管理器（仿 {@code SharedChromium}）：应用启动时经
 * {@link ProcessBuilder} 拉起 markitdown HTTP 服务，健康检查端口就绪，防重复拉起，
 * 应用关闭时销毁进程。
 *
 * <p>{@code content.markitdown.enabled=false}（默认）时不创建本 bean、不拉起任何进程。
 * {@code autoStart=true}（默认）时应用启动即拉起，失败记录日志降级（转换能力不可用，
 * 不拖垮应用）；{@code autoStart=false} 时首次 {@link #ensureStarted()} 懒启动。</p>
 *
 * <p>启动失败（命令不可执行或端口超时未就绪）后本次 JVM 生命周期内不再重试，转换调用
 * 抛「markitdown 不可用」；重启应用或修复环境后恢复。自定义 command 的监听端口须与配置
 * {@code port} 一致，否则健康检查超时。</p>
 */
@Component
@ConditionalOnProperty(prefix = "content.markitdown", name = "enabled", havingValue = "true")
public class MarkitdownServer implements SmartLifecycle, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(MarkitdownServer.class);

	/** 健康检查端口就绪等待上限（uvx 首次需下载依赖，放宽到 30s）。 */
	private static final long START_TIMEOUT_MS = 30_000;

	/** 端口探活单次连接超时（毫秒）。 */
	private static final int PROBE_TIMEOUT_MS = 1_000;

	/** 健康检查轮询间隔（毫秒）。 */
	private static final long PROBE_INTERVAL_MS = 200;

	private final MarkitdownProperties properties;
	private final Object lock = new Object();

	private volatile Process process;
	private volatile boolean started;
	private volatile boolean startFailed;

	public MarkitdownServer(MarkitdownProperties properties) {
		this.properties = properties;
	}

	/** markitdown Streamable HTTP 端点（{@code http://127.0.0.1:{port}/mcp}）。 */
	public String endpointUrl() {
		return properties.endpointUrl();
	}

	/**
	 * 确保 markitdown 已就绪；未启动则拉起并健康检查。已启动幂等返回；
	 * 启动失败或未启用时抛 {@link IllegalStateException}。
	 */
	public void ensureStarted() {
		if (started) {
			return;
		}
		synchronized (lock) {
			if (started) {
				return;
			}
			if (startFailed) {
				throw new IllegalStateException("markitdown 服务不可用：此前启动失败，请检查 content.markitdown.command 与端口配置后重启应用");
			}
			startProcess();
		}
	}

	private void startProcess() {
		Process p = null;
		try {
			List<String> tokens = tokenize(properties.getCommand());
			ProcessBuilder builder = new ProcessBuilder(tokens);
			builder.redirectErrorStream(true);
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			p = builder.start();
			waitForPortReady(p);
			process = p;
			started = true;
			log.info("markitdown 子进程已就绪：{} -> {}", tokens.get(0), endpointUrl());
		}
		catch (IOException e) {
			startFailed = true;
			kill(p);
			throw new IllegalStateException("启动 markitdown 子进程失败（command: " + properties.getCommand() + "）", e);
		}
		catch (RuntimeException e) {
			startFailed = true;
			kill(p);
			throw e;
		}
	}

	/** 健康检查：轮询端口可连接，进程提前退出则立即失败。 */
	private void waitForPortReady(Process p) throws IOException {
		long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (p.isAlive() && portOpen()) {
				return;
			}
			if (!p.isAlive()) {
				throw new IOException("markitdown 子进程提前退出（command: " + properties.getCommand() + "）");
			}
			try {
				Thread.sleep(PROBE_INTERVAL_MS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("等待 markitdown 端口就绪被中断", e);
			}
		}
		throw new IOException("markitdown 端口 " + properties.getPort() + " 在 " + START_TIMEOUT_MS + "ms 内未就绪");
	}

	private boolean portOpen() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", properties.getPort()), PROBE_TIMEOUT_MS);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	private static List<String> tokenize(String command) {
		if (command == null || command.isBlank()) {
			throw new IllegalArgumentException("content.markitdown.command 不能为空");
		}
		return new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
	}

	// ---- SmartLifecycle：autoStart=true 时应用启动拉起，失败降级不拖垮应用 ----

	@Override
	public void start() {
		try {
			ensureStarted();
		}
		catch (RuntimeException e) {
			log.error("markitdown 服务启动失败，转换能力降级不可用", e);
		}
	}

	@Override
	public void stop() {
		// 关闭统一走 DisposableBean#destroy
	}

	@Override
	public boolean isRunning() {
		return started && process != null && process.isAlive();
	}

	@Override
	public boolean isAutoStartup() {
		return properties.isAutoStart();
	}

	// ---- DisposableBean：应用关闭销毁子进程 ----

	@Override
	public void destroy() {
		kill(process);
	}

	private static void kill(Process p) {
		if (p == null) {
			return;
		}
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

}
