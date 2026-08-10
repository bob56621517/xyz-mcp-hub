package io.xyz.xyz_mcp_hub.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ContainerManager} 生命周期单测：首用拉起、防重拉、健康检查、幂等、崩溃恢复、闲置回收、关闭销毁。
 * 经 {@link DockerOps} 与 {@link PortProbe} 两个 seam 注入 fake——不启真实 docker（#32 验收门槛）。
 * 纯 JVM、无 Spring、无外部服务依赖。
 */
class ContainerManagerTest {

	@Test
	void ensureRunningPullsOnlyWhenImageMissing() {
		FakeDockerOps ops = new FakeDockerOps();
		ops.imageExists = false;
		ContainerManager manager = newManager(ops, hostPort -> true);

		manager.ensureRunning(spec("markitdown"));
		// 首次：镜像缺失 → pull 一次 → run
		assertThat(ops.pulled).containsExactly("image-markitdown");
		assertThat(ops.runs).hasSize(1);

		// 再次：已管理且运行中 → 幂等返回，不再 pull / run
		manager.ensureRunning(spec("markitdown"));
		assertThat(ops.pulled).hasSize(1);
		assertThat(ops.runs).hasSize(1);
	}

	@Test
	void ensureRunningSkipsPullWhenImageExists() {
		FakeDockerOps ops = new FakeDockerOps();
		ops.imageExists = true;
		ContainerManager manager = newManager(ops, hostPort -> true);

		manager.ensureRunning(spec("jina"));
		assertThat(ops.pulled).isEmpty();
		assertThat(ops.runs).hasSize(1);
	}

	@Test
	void ensureRunningHealthChecksHostPortAndRegisters() {
		FakeDockerOps ops = new FakeDockerOps();
		List<Integer> probed = new ArrayList<>();
		ContainerManager manager = newManager(ops, hostPort -> {
			probed.add(hostPort);
			return true;
		});

		ContainerHandle handle = manager.ensureRunning(spec("markitdown"));
		assertThat(handle.containerId()).isEqualTo("cid-1");
		assertThat(probed).containsExactly(13001);
		assertThat(manager.managedCount()).isEqualTo(1);
		assertThat(manager.managed()).extracting(ContainerHandle::containerId).containsExactly("cid-1");
	}

	@Test
	void ensureRunningHealthCheckTimeoutCleansUpFailedContainer() {
		FakeDockerOps ops = new FakeDockerOps();
		ContainerManager manager = newManager(ops, hostPort -> false);

		assertThatThrownBy(() -> manager.ensureRunning(spec("markitdown")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("健康检查超时");
		// 健康检查失败 → 失败容器被清理、不留在受管表
		assertThat(ops.stopped).containsExactly("cid-1");
		assertThat(manager.managedCount()).isZero();
	}

	@Test
	void ensureRunningRecoversCrashedContainer() {
		FakeDockerOps ops = new FakeDockerOps();
		ops.imageExists = false;
		ContainerManager manager = newManager(ops, hostPort -> true);

		manager.ensureRunning(spec("markitdown")); // 首次：镜像缺失 → pull
		ops.running = false; // 容器异常退出
		manager.ensureRunning(spec("markitdown"));

		assertThat(ops.runs).hasSize(2); // 重新拉起
		assertThat(ops.pulled).hasSize(1); // 镜像已拉取过，崩溃恢复不再 pull
		assertThat(manager.managedCount()).isEqualTo(1);
	}

	@Test
	void isHealthyDelegatesToPortProbe() {
		FakeDockerOps ops = new FakeDockerOps();
		ContainerManager manager = newManager(ops, hostPort -> hostPort == 13001);
		assertThat(manager.isHealthy(spec("markitdown"))).isTrue();
		assertThat(manager.isHealthy(new ContainerSpec("other", "img", Protocol.MCP, 3001, 19999))).isFalse();
	}

	@Test
	void idleReclaimRecyclesExpiredContainers() {
		FakeDockerOps ops = new FakeDockerOps();
		ContainerManager manager = newManager(ops, hostPort -> true);
		manager.ensureRunning(spec("markitdown"));

		// 模拟闲置超过 TTL：now 前进到未来
		long future = System.nanoTime() + 10_000_000_000L;
		manager.scanIdle(future, 1_000_000_000L);

		assertThat(ops.stopped).containsExactly("cid-1");
		assertThat(manager.managedCount()).isZero();
	}

	@Test
	void recentlyTouchedContainerNotReclaimed() {
		FakeDockerOps ops = new FakeDockerOps();
		ContainerManager manager = newManager(ops, hostPort -> true);
		manager.ensureRunning(spec("markitdown"));

		// 当前时刻扫描：容器刚 touch，未超 TTL → 不回收
		manager.scanIdle(System.nanoTime(), 1_000_000_000L);
		assertThat(ops.stopped).isEmpty();
		assertThat(manager.managedCount()).isEqualTo(1);
	}

	@Test
	void stopAndRemoveBySpecReturnsWhetherRemoved() {
		FakeDockerOps ops = new FakeDockerOps();
		ContainerManager manager = newManager(ops, hostPort -> true);
		manager.ensureRunning(spec("markitdown"));

		assertThat(manager.stopAndRemove(spec("markitdown"))).isTrue();
		assertThat(ops.stopped).containsExactly("cid-1");
		assertThat(manager.stopAndRemove(spec("markitdown"))).isFalse();
	}

	@Test
	void destroyStopsAllManagedContainers() {
		FakeDockerOps ops = new FakeDockerOps();
		ContainerManager manager = newManager(ops, hostPort -> true);
		manager.ensureRunning(spec("markitdown"));
		manager.ensureRunning(spec("jina"));

		manager.destroy();

		assertThat(ops.stopped).containsExactlyInAnyOrder("cid-1", "cid-2");
		assertThat(manager.managedCount()).isZero();
	}

	// ---------- 工具 ----------

	private static ContainerManager newManager(DockerOps ops, PortProbe probe) {
		DockerProperties props = new DockerProperties();
		props.setTtlSeconds(1);
		props.setScanIntervalSeconds(60);
		props.setStartTimeoutSeconds(1);
		return new ContainerManager(ops, props, probe);
	}

	private static ContainerSpec spec(String name) {
		return new ContainerSpec(name, "image-" + name, Protocol.MCP, 3001, 13001);
	}

	/** 可注入的 fake docker 门面：记录调用，镜像存在性/运行中可切换。 */
	private static final class FakeDockerOps implements DockerOps {

		boolean imageExists = true;
		boolean running = true;
		final List<String> pulled = new ArrayList<>();
		final List<ContainerSpec> runs = new ArrayList<>();
		final List<String> stopped = new ArrayList<>();
		private int idSeq;

		@Override
		public boolean imageExists(String image) {
			return imageExists;
		}

		@Override
		public void pull(String image) {
			pulled.add(image);
			imageExists = true;
		}

		@Override
		public String run(ContainerSpec spec) {
			runs.add(spec);
			return "cid-" + (++idSeq);
		}

		@Override
		public boolean isRunning(String containerId) {
			return running;
		}

		@Override
		public void stopAndRemove(String containerId) {
			stopped.add(containerId);
		}
	}
}
