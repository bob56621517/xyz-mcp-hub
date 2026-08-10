package io.xyz.xyz_mcp_hub.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * docker 顶级模块配置（前缀 {@code docker}）：容器生命周期管理（{@link ContainerManager}）的可配项。
 * {@code manifest-path} 指向 mvn 生成的 {@code manifests/mcp-images.yaml}（容器运行规范）；
 * 其余为容器拉起 / 健康检查 / 闲置回收的时序与超时。
 */
@ConfigurationProperties(prefix = "docker")
public class DockerProperties {

	/** 是否启用容器运行时（默认 true；false 时不创建 DockerOps / ContainerManager bean）。 */
	private boolean enabled = true;

	/** 镜像清单路径（mvn verify 从 mcp-images.yaml.tpl 生成，ContainerSpecReader 读取）。 */
	private String manifestPath = "manifests/mcp-images.yaml";

	/** 容器闲置自动回收时长（秒，默认 600）。 */
	private long ttlSeconds = 600;

	/** 后台闲置回收扫描周期（秒，默认 60）。 */
	private long scanIntervalSeconds = 60;

	/** 容器启动后健康检查（宿主端口就绪）等待上限（秒，默认 60）。 */
	private long startTimeoutSeconds = 60;

	/** 镜像拉取超时（秒，默认 300）。 */
	private long pullTimeoutSeconds = 300;

	/** docker CLI 可执行名（默认 {@code docker}）。 */
	private String command = "docker";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getManifestPath() {
		return manifestPath;
	}

	public void setManifestPath(String manifestPath) {
		this.manifestPath = manifestPath;
	}

	public long getTtlSeconds() {
		return ttlSeconds;
	}

	public void setTtlSeconds(long ttlSeconds) {
		this.ttlSeconds = ttlSeconds;
	}

	public long getScanIntervalSeconds() {
		return scanIntervalSeconds;
	}

	public void setScanIntervalSeconds(long scanIntervalSeconds) {
		this.scanIntervalSeconds = scanIntervalSeconds;
	}

	public long getStartTimeoutSeconds() {
		return startTimeoutSeconds;
	}

	public void setStartTimeoutSeconds(long startTimeoutSeconds) {
		this.startTimeoutSeconds = startTimeoutSeconds;
	}

	public long getPullTimeoutSeconds() {
		return pullTimeoutSeconds;
	}

	public void setPullTimeoutSeconds(long pullTimeoutSeconds) {
		this.pullTimeoutSeconds = pullTimeoutSeconds;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}
}
