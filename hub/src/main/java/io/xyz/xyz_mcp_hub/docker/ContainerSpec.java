package io.xyz.xyz_mcp_hub.docker;

import java.util.Objects;

/**
 * 容器运行规范（ADR-0011 决策 4）：{@code image} 镜像名、{@code protocol} 接入协议（mcp | rest）、
 * {@code port} 容器内监听端口（镜像固定）、{@code hostPort} 宿主映射端口（一律 5 位数、避开 8080/8081
 * 等常用端口）。与 {@code manifests/mcp-images.yaml} 每个 {@code images:} 节点一一对应。
 *
 * @param name 清单键名（源名，如 {@code markitdown} / {@code jina}）
 */
public record ContainerSpec(String name, String image, Protocol protocol, int port, int hostPort) {

	public ContainerSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(image, "image");
		Objects.requireNonNull(protocol, "protocol");
		if (name.isBlank()) {
			throw new IllegalArgumentException("ContainerSpec.name 不能为空");
		}
		if (image.isBlank()) {
			throw new IllegalArgumentException("ContainerSpec.image 不能为空：" + name);
		}
		checkPort("port", port);
		checkHostPort(hostPort);
	}

	/** 该容器的保留名（幂等拉起 / 清理残留用）：{@code xyz-hub-{name}}。 */
	public String containerName() {
		return "xyz-hub-" + name;
	}

	private static void checkPort(String field, int value) {
		if (value <= 0 || value > 65535) {
			throw new IllegalArgumentException("ContainerSpec." + field + " 越界：" + value);
		}
	}

	/** hostPort 为宿主映射端口：按 spec 一律 5 位数（避开 8080/8081 等常用端口）。 */
	private static void checkHostPort(int hostPort) {
		if (hostPort < 10_000 || hostPort > 99_999) {
			throw new IllegalArgumentException("ContainerSpec.hostPort 应为 5 位数：" + hostPort);
		}
	}
}
