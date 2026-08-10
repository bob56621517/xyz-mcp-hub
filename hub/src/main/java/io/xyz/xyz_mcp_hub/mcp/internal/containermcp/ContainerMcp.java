package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.util.List;
import java.util.Optional;

import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * 容器 MCP 源基类（ADR-0009 四类 MCP 之一，protocol=mcp 型，#37）：本地容器按需拉起 → 转发其 MCP 工具。
 *
 * <p>容器生命周期（首用拉起 + 防重拉 + 闲置回收 + 关闭销毁）全部委托 {@code docker} 模块的
 * {@link ContainerManager}；工具清单由子类以静态冒烟数据声明（镜像由我们 pin，工具集与所 pin 镜像版本
 * 绑定，ADR-0011 决策 5）；工具调用经 {@link ContainerMcpClient} 转发到容器内 MCP 端点。</p>
 *
 * <p>优雅降级（#37 验收）：docker 运行时未启用（{@code docker.enabled=false}，ContainerManager bean
 * 不存在）或镜像清单缺失本源 mcp 规格时 {@link #isEnabled()} 返回 {@code false}，源不出现在工具目录、
 * 不拖垮 hub；工具调用失败由工具层返回友好文本（见 {@code MarkitdownTools}）。</p>
 */
public abstract class ContainerMcp implements McpEndpointProvider {

	private static final Logger log = LoggerFactory.getLogger(ContainerMcp.class);

	private final ContainerManager containerManager;
	private final ContainerSpecReader specReader;
	private final ContainerMcpClient client;

	protected ContainerMcp(ContainerManager containerManager, ContainerSpecReader specReader,
			ContainerEndpoint endpoint) {
		this.containerManager = containerManager;
		this.specReader = specReader;
		// client 持有 containerManager：重试 initialize 期间 touch 容器，防短 TTL 回收误删重试中的容器
		this.client = new ContainerMcpClient(endpoint, containerManager);
	}

	@Override
	public final Scope getScope() {
		return Scope.NETWORK;
	}

	/** 目录元数据（#34）：容器源 type=CONTAINER（protocol 取容器规格，见 {@link #getProtocol()}）。 */
	@Override
	public SourceType getSourceType() {
		return SourceType.CONTAINER;
	}

	/** 容器接入协议（目录元数据 protocol 字段；isEnabled 已保证规格存在）。 */
	public Protocol getProtocol() {
		return requireSpec().protocol();
	}

	/** 静态冒烟工具清单（子类声明，镜像 pin 绑定）。 */
	@Override
	public abstract List<ToolCallback> getTools();

	@Override
	public boolean isEnabled() {
		if (containerManager == null) {
			log.debug("docker 容器运行时未启用（docker.enabled=false），ContainerMcp 源 {} 降级禁用", getName());
			return false;
		}
		return findMcpSpec().isPresent();
	}

	/** 本源对应容器规格（isEnabled 已保证存在；调用路径缺失时抛异常说明，正常不应发生）。 */
	protected ContainerSpec requireSpec() {
		return findMcpSpec().orElseThrow(() -> new IllegalStateException("清单缺少 " + getName()
			+ " 的 mcp 型容器规格（manifests/mcp-images.yaml 是否已由 mvn verify 生成）"));
	}

	/** 本源 mcp 型容器规格（镜像清单按源名取，过滤 protocol=mcp）。 */
	private Optional<ContainerSpec> findMcpSpec() {
		return specReader.byName(getName()).filter(spec -> spec.protocol() == Protocol.MCP);
	}

	/** MCP 转发客户端（内部持有 containerManager：首用拉起 + 重试 touch）。 */
	protected ContainerMcpClient client() {
		return client;
	}
}
