package io.xyz.xyz_mcp_hub.docker;

/**
 * 单个已拉起容器的句柄：持有 spec 与容器 id，{@link #lastAccessNanos()} 供注册表 TTL 闲置回收判断。
 */
public final class ContainerHandle {

	private final ContainerSpec spec;
	private final String containerId;
	private volatile long lastAccessNanos;

	public ContainerHandle(ContainerSpec spec, String containerId) {
		this.spec = spec;
		this.containerId = containerId;
		touch();
	}

	public ContainerSpec spec() {
		return spec;
	}

	public String containerId() {
		return containerId;
	}

	/** 记录一次访问（每次 {@link ContainerManager#ensureRunning} 命中时更新）。 */
	public void touch() {
		lastAccessNanos = System.nanoTime();
	}

	long lastAccessNanos() {
		return lastAccessNanos;
	}
}
