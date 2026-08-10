package io.xyz.xyz_mcp_hub.docker;

import java.io.IOException;
import java.nio.file.Path;

import io.xyz.xyz_mcp_hub.docker.internal.DockerCliOps;

/**
 * 真实 docker 冒烟（手工运行，非自动测试）——#32 验收：拉起一个容器 + 健康检查 + 闲置回收 + 关闭销毁。
 *
 * <p>运行：{@code ./mvnw exec:java -pl hub -Dexec.mainClass=io.xyz.xyz_mcp_hub.docker.DockerContainerSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}
 * （在仓库根目录执行，保证默认 manifest-path 指向生成的 manifests/mcp-images.yaml；须加 {@code -pl hub}，
 * 根聚合 pom 不含测试类）。</p>
 *
 * @requires-docker 需本机 docker daemon 可用（docker info 通过）；markitdown sidecar 镜像需先
 * {@code ./mvnw install} 构建、清单需 {@code ./mvn verify} 生成（镜像/清单缺失时冒烟内会给出提示并退出）
 */
public class DockerContainerSmoke {

	public static void main(String[] args) {
		DockerProperties props = new DockerProperties();
		String dockerCmd = props.getCommand();

		System.out.println("[1/6] 依赖检查：docker CLI 可用性");
		if (!dockerAvailable(dockerCmd)) {
			System.out.println("      docker 不可用（" + dockerCmd + "），退出");
			return;
		}
		System.out.println("      docker OK");

		System.out.println("[2/6] 读取镜像清单 manifest → markitdown spec");
		ContainerSpecReader reader = new ContainerSpecReader(Path.of(props.getManifestPath()));
		ContainerSpec markitdown = reader.byName("markitdown").orElse(null);
		if (markitdown == null) {
			System.out.println("      清单缺少 markitdown 节点（manifest 由 mvn verify 生成），退出");
			return;
		}
		System.out.println("      spec: " + markitdown);

		DockerCliOps ops = new DockerCliOps(props);
		System.out.println("[3/6] 镜像存在性（markitdown sidecar 需先 mvn install 构建）");
		if (!ops.imageExists(markitdown.image())) {
			System.out.println("      镜像缺失：" + markitdown.image() + "，先执行 ./mvnw install，退出");
			return;
		}
		System.out.println("      镜像存在：" + markitdown.image());

		System.out.println("[4/6] 首用拉起 + 健康检查：ensureRunning");
		ContainerManager manager = new ContainerManager(ops, props);
		ContainerHandle handle = manager.ensureRunning(markitdown);
		System.out.println("      容器就绪：" + handle.containerId()
			+ " -> 127.0.0.1:" + markitdown.hostPort() + "（健康检查通过）");
		System.out.println("      受管容器数=" + manager.managedCount());

		System.out.println("[5/6] 闲置回收：强制 scanIdle 模拟闲置超过 TTL（" + props.getTtlSeconds() + "s）");
		// 模拟时钟前进到「超过 TTL」的时刻（配置 TTL + 1s 余量），触发闲置回收
		long ttlNanos = props.getTtlSeconds() * 1_000_000_000L;
		long future = System.nanoTime() + ttlNanos + 1_000_000_000L;
		manager.scanIdle(future, ttlNanos);
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 0，容器已回收）");

		System.out.println("[6/6] 关闭销毁：重新拉起后 destroy");
		handle = manager.ensureRunning(markitdown);
		System.out.println("      重新拉起：" + handle.containerId());
		manager.destroy();
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 0，已关闭销毁）");

		boolean ok = manager.managedCount() == 0;
		System.out.println("结论：" + (ok ? "通过（容器全生命周期可用）" : "未通过（见上方输出）"));
	}

	private static boolean dockerAvailable(String dockerCmd) {
		try {
			Process process = new ProcessBuilder(dockerCmd, "info").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor() == 0;
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}
}
