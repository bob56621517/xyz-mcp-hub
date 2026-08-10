package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.DockerProperties;
import io.xyz.xyz_mcp_hub.docker.internal.DockerCliOps;
import org.springframework.ai.tool.ToolCallback;

/**
 * 真实 docker 冒烟（手工运行，非自动测试）——#37 验收：首用拉起 markitdown 容器 + 真实 MCP 转发
 * {@code convert_to_markdown} + 容器绑 127.0.0.1 / 隔离网络 + SSRF 预检 + 闲置回收。
 *
 * <p>运行：{@code ./mvnw exec:java -pl hub -Dexec.mainClass=io.xyz.xyz_mcp_hub.mcp.internal.containermcp.MarkitdownContainerMcpSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}
 * （在仓库根目录执行，保证默认 manifest-path 指向生成的 manifests/mcp-images.yaml；须加 {@code -pl hub}）。</p>
 *
 * @requires-docker 需本机 docker daemon 可用（docker info 通过）；markitdown sidecar 镜像需先
 * {@code ./mvnw install} 构建、清单需 {@code ./mvnw verify} 生成（镜像/清单缺失时冒烟内给出提示并退出）
 */
public class MarkitdownContainerMcpSmoke {

	private static final String DATA_URI = "data:text/plain,hello markitdown";
	private static final String PRIVATE_URL = "http://127.0.0.1:8080/internal";

	public static void main(String[] args) {
		DockerProperties props = new DockerProperties();
		// 冒烟用短 TTL / 短扫描周期，验证后台闲置回收线程真实回收（生产默认 TTL=600s 过长）
		props.setTtlSeconds(3);
		props.setScanIntervalSeconds(1);
		props.setStartTimeoutSeconds(60);
		String dockerCmd = props.getCommand();

		System.out.println("[1/9] 依赖检查：docker CLI 可用性");
		if (!dockerAvailable(dockerCmd)) {
			System.out.println("      docker 不可用（" + dockerCmd + "），退出");
			return;
		}
		System.out.println("      docker OK");

		System.out.println("[2/9] 读取镜像清单 manifest → markitdown spec");
		ContainerSpecReader reader = new ContainerSpecReader(Path.of(props.getManifestPath()));
		ContainerSpec markitdown = reader.byName("markitdown").orElse(null);
		if (markitdown == null) {
			System.out.println("      清单缺少 markitdown 节点（manifest 由 mvn verify 生成），退出");
			return;
		}
		System.out.println("      spec: " + markitdown);

		DockerCliOps ops = new DockerCliOps(props);
		System.out.println("[3/9] 镜像存在性（markitdown sidecar 需先 mvn install 构建）");
		if (!ops.imageExists(markitdown.image())) {
			System.out.println("      镜像缺失：" + markitdown.image() + "，先执行 ./mvnw install，退出");
			return;
		}
		System.out.println("      镜像存在：" + markitdown.image());

		ContainerManager manager = new ContainerManager(ops, props);
		System.out.println("[4/8] 首次调用拉起容器 + 真实 MCP 转发 convert_to_markdown（data: uri）");
		System.out.println("      （容器尚未启动；工具调用经 ContainerMcpClient 内部 ensureRunning 首用拉起 + 健康检查 + initialize 重试）");
		MarkitdownContainerMcp provider = new MarkitdownContainerMcp(manager, reader, ContainerEndpoint.hostPort());
		ToolCallback tool = provider.getTools().get(0);
		String md = tool.call("{\"uri\":\"" + DATA_URI + "\"}");
		System.out.println("      返回 Markdown:\n------\n" + md + "\n------");
		// @Tool 返回 String 会被 JSON 编码（全库既有行为），用 contains 判定
		boolean converted = md != null && !md.isBlank() && md.contains("hello markitdown");
		System.out.println("      受管容器数=" + manager.managedCount() + "（首用拉起后应 1）");
		System.out.println("      判定：" + (converted ? "通过（首次调用拉起容器 + 真实转换返回 markdown）" : "失败"));

		System.out.println("[5/8] 验收：容器绑 127.0.0.1 + 隔离网络（docker inspect）");
		String cid = manager.managed().stream().map(ContainerHandle::containerId).findFirst().orElse("无");
		String portBindings = dockerInspect(cid, "{{json .HostConfig.PortBindings}}");
		String networks = dockerInspect(cid, "{{json .NetworkSettings.Networks}}");
		System.out.println("      PortBindings: " + portBindings);
		System.out.println("      Networks:     " + networks);
		boolean bound = portBindings.contains("127.0.0.1") && networks.contains("xyz-mcp-hub");
		System.out.println("      判定：" + (bound ? "通过（绑 127.0.0.1 + 隔离网络 xyz-mcp-hub）" : "失败"));

		System.out.println("[6/8] 验收：SSRF 预检拦截内网地址（http(s) uri 交给容器前）");
		String guarded = tool.call("{\"uri\":\"" + PRIVATE_URL + "\"}");
		System.out.println("      结果: " + guarded);
		boolean ssrf = guarded.contains("SSRF 防护拦截");
		System.out.println("      判定：" + (ssrf ? "通过（内网地址被拒）" : "失败"));

		System.out.println("[7/8] 验收：闲置回收（后台扫描线程，TTL=" + props.getTtlSeconds() + "s / 扫描="
			+ props.getScanIntervalSeconds() + "s，等待后台回收）");
		// 上次触达后超过 TTL，后台 daemon 扫描线程应回收容器（无需手工调用包私有 scanIdle）
		sleepSeconds(props.getTtlSeconds() + props.getScanIntervalSeconds() + 4);
		boolean reclaimed = manager.managedCount() == 0;
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 0，容器已被后台回收线程移除）");
		System.out.println("      判定：" + (reclaimed ? "通过（闲置容器被回收）" : "失败"));

		System.out.println("[8/8] 关闭销毁：重新拉起后 destroy");
		manager.ensureRunning(markitdown);
		manager.destroy();
		boolean destroyed = manager.managedCount() == 0;
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 0，已关闭销毁）");
		System.out.println("      判定：" + (destroyed ? "通过（关闭销毁）" : "失败"));

		boolean ok = converted && bound && ssrf && reclaimed && destroyed;
		System.out.println("结论：" + (ok ? "通过（首用拉起 + MCP 转发 + 网络隔离 + SSRF 预检 + 闲置回收 全部可用）"
			: "未通过（见上方输出）"));
	}

	private static void sleepSeconds(long seconds) {
		try {
			Thread.sleep(seconds * 1_000L);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean dockerAvailable(String dockerCmd) {
		return run(dockerCmd, "info").exitCode == 0;
	}

	private static String dockerInspect(String containerId, String format) {
		return run("docker", "inspect", containerId, "--format", format).output;
	}

	private static ExecResult run(String... tokens) {
		try {
			Process process = new ProcessBuilder(tokens).redirectErrorStream(true).start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int code = process.waitFor();
			return new ExecResult(code, out);
		}
		catch (IOException | InterruptedException e) {
			return new ExecResult(-1, e.getMessage());
		}
	}

	private record ExecResult(int exitCode, String output) {
	}
}
