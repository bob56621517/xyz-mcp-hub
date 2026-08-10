package io.xyz.xyz_mcp_hub.docker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * docker 顶级模块的容器生命周期管理器（ADR-0012，与 playwright 同级）：首用拉起 + 防重拉 +
 * 健康检查 + 闲置回收 + 关闭销毁。
 *
 * <p>全部 docker 原子操作经 {@link DockerOps} seam 执行、健康检查经 {@link PortProbe} seam 执行，
 * 单测注入 fake 即可验证生命周期逻辑，不依赖真实 docker（#32 验收门槛）。生产默认：{@code DockerCliOps}
 * 走 docker CLI、{@code defaultPortProbe} 探宿主端口。</p>
 *
 * <p>幂等：{@link #ensureRunning} 对已管理且运行中的容器直接返回；容器异常退出则重新拉起；
 * 镜像本地缺失才 pull（防重拉）。后台 daemon 线程按 {@code docker.scan-interval-seconds} 周期扫描，
 * 回收超过 {@code docker.ttl-seconds} 未操作的容器；应用关闭时停止并删除全部受管容器。</p>
 */
@Component
@ConditionalOnProperty(prefix = "docker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContainerManager implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(ContainerManager.class);

	/** 健康检查轮询间隔（毫秒）。 */
	private static final long PROBE_INTERVAL_MS = 200;

	/** 端口探活单次连接超时（毫秒）。 */
	private static final int PROBE_TIMEOUT_MS = 1_000;

	private final DockerOps dockerOps;
	private final PortProbe portProbe;
	private final long ttlNanos;
	private final long scanIntervalMillis;
	private final long startTimeoutMillis;

	private final Map<String, ContainerHandle> managed = new ConcurrentHashMap<>();
	private final Object lock = new Object();
	private final Thread scanner;
	private volatile boolean running = true;

	@Autowired
	public ContainerManager(DockerOps dockerOps, DockerProperties properties) {
		this(dockerOps, properties, ContainerManager::defaultPortProbe);
	}

	/** 测试注入 seam：portProbe 可由测试替换为 fake。 */
	ContainerManager(DockerOps dockerOps, DockerProperties properties, PortProbe portProbe) {
		this.dockerOps = dockerOps;
		this.portProbe = portProbe;
		this.ttlNanos = Math.max(1, properties.getTtlSeconds()) * 1_000_000_000L;
		this.scanIntervalMillis = Math.max(1, properties.getScanIntervalSeconds()) * 1_000L;
		this.startTimeoutMillis = Math.max(1, properties.getStartTimeoutSeconds()) * 1_000L;
		this.scanner = new Thread(this::scanLoop, "container-idle-reclaimer");
		this.scanner.setDaemon(true);
		this.scanner.start();
	}

	/**
	 * 首用拉起 + 防重拉 + 健康检查：确保 spec 对应容器已就绪并返回其句柄。
	 * 已管理且运行中 → 幂等返回；镜像缺失 → pull 一次；健康检查超时 → 清理失败容器并抛异常。
	 */
	public ContainerHandle ensureRunning(ContainerSpec spec) {
		synchronized (lock) {
			ContainerHandle existing = managed.get(spec.name());
			if (existing != null) {
				if (dockerOps.isRunning(existing.containerId())) {
					existing.touch();
					return existing;
				}
				log.warn("容器 {} 已退出，重新拉起（spec: {}）", existing.containerId(), spec.name());
				managed.remove(spec.name());
			}
			if (!dockerOps.imageExists(spec.image())) {
				log.info("镜像 {} 本地缺失，pull（防重拉：仅缺失时拉取）", spec.image());
				dockerOps.pull(spec.image());
			}
			String containerId = dockerOps.run(spec);
			try {
				waitForPortReady(spec.hostPort());
			}
			catch (RuntimeException e) {
				try {
					dockerOps.stopAndRemove(containerId);
				}
				catch (RuntimeException cleanup) {
					log.warn("清理健康检查失败容器 {} 失败：{}", containerId, cleanup.getMessage());
				}
				throw e;
			}
			ContainerHandle handle = new ContainerHandle(spec, containerId);
			handle.touch();
			managed.put(spec.name(), handle);
			log.info("容器已就绪：{} ({}) -> 127.0.0.1:{}", spec.name(), containerId, spec.hostPort());
			return handle;
		}
	}

	/** 健康检查：宿主端口是否就绪（探 127.0.0.1:hostPort，不依赖容器状态）。 */
	public boolean isHealthy(ContainerSpec spec) {
		return portProbe.probe(spec.hostPort());
	}

	/** 全部受管容器句柄（只读快照）。 */
	public List<ContainerHandle> managed() {
		return List.copyOf(managed.values());
	}

	/** 受管容器数。 */
	public int managedCount() {
		return managed.size();
	}

	/** 按 spec 关闭并移除容器；返回是否确有容器被移除。 */
	public boolean stopAndRemove(ContainerSpec spec) {
		synchronized (lock) {
			ContainerHandle handle = managed.remove(spec.name());
			if (handle == null) {
				return false;
			}
			stopAndRemoveQuietly(spec.name(), handle);
			return true;
		}
	}

	/**
	 * 回收所有闲置超过 {@code ttlNanos} 的容器（供后台扫描线程与冒烟测试调用）。
	 *
	 * <p>与 {@link #ensureRunning} 持同一 {@code lock}，避免 TOCTOU：扫描判断过期与容器被并发触达
	 * （touch）之间的窗口内误回收刚启用的容器。</p>
	 */
	void scanIdle(long nowNanos, long ttlNanos) {
		synchronized (lock) {
			managed.forEach((name, handle) -> {
				if (nowNanos - handle.lastAccessNanos() > ttlNanos && managed.remove(name, handle)) {
					stopAndRemoveQuietly(name, handle);
				}
			});
		}
	}

	/** 停止并删除单个受管容器；失败仅告警，不中断整体清理。 */
	private void stopAndRemoveQuietly(String name, ContainerHandle handle) {
		try {
			dockerOps.stopAndRemove(handle.containerId());
			log.info("容器已移除：{} ({})", name, handle.containerId());
		}
		catch (RuntimeException e) {
			log.warn("移除容器 {} 失败：{}", handle.containerId(), e.getMessage());
		}
	}

	private void scanLoop() {
		try {
			// 首轮先睡一个周期，避免应用刚启动就把新建容器误判过期
			Thread.sleep(scanIntervalMillis);
		}
		catch (InterruptedException e) {
			return;
		}
		while (running) {
			scanIdle(System.nanoTime(), ttlNanos);
			try {
				Thread.sleep(scanIntervalMillis);
			}
			catch (InterruptedException e) {
				return;
			}
		}
	}

	private void waitForPortReady(int hostPort) {
		long deadline = System.currentTimeMillis() + startTimeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (portProbe.probe(hostPort)) {
				return;
			}
			try {
				Thread.sleep(PROBE_INTERVAL_MS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("等待容器端口就绪被中断：127.0.0.1:" + hostPort);
			}
		}
		throw new IllegalStateException("容器健康检查超时：127.0.0.1:" + hostPort
			+ " 在 " + startTimeoutMillis + "ms 内未就绪");
	}

	/** 默认端口探针：连接 127.0.0.1:hostPort。 */
	static boolean defaultPortProbe(int hostPort) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", hostPort), PROBE_TIMEOUT_MS);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	@Override
	public void destroy() {
		running = false;
		scanner.interrupt();
		// 关闭路径不持 lock：应用正在关闭、不再有并发 ensureRunning，且避免被长 pull 拖住
		managed.forEach((name, handle) -> stopAndRemoveQuietly(name, handle));
		managed.clear();
	}
}
