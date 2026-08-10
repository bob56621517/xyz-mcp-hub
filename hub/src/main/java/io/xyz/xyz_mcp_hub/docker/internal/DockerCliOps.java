package io.xyz.xyz_mcp_hub.docker.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.DockerOps;
import io.xyz.xyz_mcp_hub.docker.DockerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 经 docker CLI 子进程实现的 {@link DockerOps}（与 sidecar 构建的 buildx 调用同源，ADR-0012 / #31）。
 *
 * <p>只做容器编排、不做 MCP 集成——集成对象是容器暴露的 HTTP/MCP 端点，因此不违反
 * 「MCP 集成永不用 stdio 子进程」铁则（该铁则针对的是 MCP server 本身以子进程形态接入）。
 * 单测经 {@code DockerOps} seam 注入 fake，不执行本类（#32 验收门槛）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "docker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DockerCliOps implements DockerOps {

	private static final Logger log = LoggerFactory.getLogger(DockerCliOps.class);

	/** 容器隔离网络名（ADR-0010 兜底：放隔离网络，限制容器对宿主内网/保留段的可达性）。 */
	static final String NETWORK = "xyz-mcp-hub";

	private final String command;
	private final long pullTimeoutSeconds;

	public DockerCliOps(DockerProperties properties) {
		this.command = properties.getCommand();
		this.pullTimeoutSeconds = Math.max(1, properties.getPullTimeoutSeconds());
	}

	@Override
	public boolean imageExists(String image) {
		return exec("image", "inspect", image).exitCode() == 0;
	}

	@Override
	public void pull(String image) {
		log.info("docker pull {}", image);
		execWithTimeout("pull", image);
	}

	@Override
	public String run(ContainerSpec spec) {
		// 同名容器已在运行 → 复用（首用拉起幂等：外部已手工拉起 / 上次运行仍在，视为就绪直接复用，不重建）
		String runningId = runningContainerId(spec.containerName());
		if (!runningId.isEmpty()) {
			log.info("容器 {} 已在运行（{}），复用", spec.containerName(), runningId);
			return runningId;
		}
		// 清理同名残留（已停止的同名容器，docker run 会因名字冲突失败）；容忍失败
		exec("rm", "-f", spec.containerName());
		ensureNetwork();
		ExecResult result = exec("run", "-d",
			"--name", spec.containerName(),
			"--network", NETWORK,
			"-p", "127.0.0.1:" + spec.hostPort() + ":" + spec.port(),
			spec.image());
		String containerId = result.output().trim();
		if (containerId.isEmpty()) {
			throw new IllegalStateException("docker run 未返回容器 id（exit " + result.exitCode() + "）");
		}
		return containerId;
	}

	@Override
	public boolean isRunning(String containerId) {
		ExecResult result = exec("ps", "-q", "--filter", "id=" + containerId);
		return result.exitCode() == 0 && !result.output().isBlank();
	}

	/** 运行中的同名容器 id（无则空串）；docker ps 按精确名过滤（运行中才出现在 ps 输出）。 */
	private String runningContainerId(String name) {
		ExecResult result = exec("ps", "-q", "--filter", "name=^" + name + "$");
		return result.exitCode() == 0 ? result.output().trim() : "";
	}

	@Override
	public void stopAndRemove(String containerId) {
		exec("rm", "-f", containerId);
	}

	/** 隔离网络不存在则创建；已存在（含并发创建竞争）容忍失败。 */
	private void ensureNetwork() {
		if (exec("network", "inspect", NETWORK).exitCode() == 0) {
			return;
		}
		exec("network", "create", NETWORK);
	}

	/** 执行 docker 命令并等待完成；非零退出码不抛（由调用方按语义判断）。 */
	private ExecResult exec(String... args) {
		return execCommand(args, false);
	}

	/** 执行 docker 命令并等待完成；拉取超时强制销毁进程并抛异常。 */
	private ExecResult execWithTimeout(String... args) {
		return execCommand(args, true);
	}

	private ExecResult execCommand(String[] args, boolean withTimeout) {
		List<String> tokens = new ArrayList<>();
		tokens.add(command);
		tokens.addAll(Arrays.asList(args));
		try {
			ProcessBuilder builder = new ProcessBuilder(tokens);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			// 边读边等：后台线程持续排空合并输出流，避免大输出（如 docker pull 进度）填满
			// OS 管道缓冲后子进程阻塞写、永不退出（先 waitFor 再 readAllBytes 会死锁）。
			CompletableFuture<String> drained = CompletableFuture.supplyAsync(() -> readOutput(process));
			if (withTimeout && !process.waitFor(pullTimeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				drained.cancel(true);
				throw new IllegalStateException("docker 命令超时（" + pullTimeoutSeconds + "s）："
					+ String.join(" ", tokens));
			}
			process.waitFor();
			return new ExecResult(process.exitValue(), drained.join());
		}
		catch (IOException e) {
			throw new IllegalStateException("docker 命令不可执行：" + command
				+ "（是否已安装并加入 PATH？原命令：docker " + String.join(" ", args) + "）", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("docker 命令被中断：" + String.join(" ", args), e);
		}
	}

	/** 排空子进程合并输出流（redirectErrorStream 后 stdout 含 stderr），直到 EOF。 */
	private static String readOutput(Process process) {
		try {
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		catch (IOException e) {
			return "";
		}
	}

	private record ExecResult(int exitCode, String output) {
	}
}
