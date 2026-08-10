package io.xyz.xyz_mcp_hub.docker;

/**
 * docker 运行时操作的门面 seam（ADR-0012）：容器生命周期管理所依赖的 docker 原子操作。
 * 独立为接口便于单测注入 fake——单测不依赖真实 docker（#32 验收门槛）。
 *
 * <p>真实实现 {@code DockerCliOps}（{@code docker.internal} 包）经 docker CLI 子进程执行；
 * 其他模块只依赖本接口与 {@link ContainerManager}，不依赖 CLI 实现细节。</p>
 */
public interface DockerOps {

	/** 镜像是否已存在于本地（防重拉判定）。 */
	boolean imageExists(String image);

	/** 拉取镜像（本地缺失时调用；超时抛异常）。 */
	void pull(String image);

	/**
	 * 创建并后台启动容器（绑 {@code 127.0.0.1:hostPort:port}、放隔离网络），返回容器 id；
	 * 容器保留名 {@link ContainerSpec#containerName()}，同名残留容器在实现内部先清理。
	 */
	String run(ContainerSpec spec);

	/** 容器是否运行中。 */
	boolean isRunning(String containerId);

	/** 强制停止并删除容器。 */
	void stopAndRemove(String containerId);
}
