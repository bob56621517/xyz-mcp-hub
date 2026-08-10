package io.xyz.xyz_mcp_hub.docker;

/**
 * 宿主端口就绪探针（健康检查）：返回 {@code 127.0.0.1:hostPort} 是否可连接。
 *
 * <p>独立为可注入 seam——单测注入 fake 探针即可在不启真实容器的情况下验证生命周期逻辑；
 * 生产默认实现见 {@link ContainerManager#defaultPortProbe(int)}。</p>
 */
@FunctionalInterface
public interface PortProbe {

	boolean probe(int hostPort);
}
