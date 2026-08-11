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
 * 容器 MCP 源基类（ADR-0009 四类 MCP 之一，#37 mcp 型 / #38 rest 型）：本地容器按需拉起 → 接入容器能力。
 *
 * <p>子类声明接入协议（{@link #protocol()}）：{@code mcp} 型（如 markitdown）转发容器内 MCP 工具，
 * 工具调用经 {@link ContainerMcpClient} 转发；{@code rest} 型（如 jina）JVM 薄包装容器 REST API，
 * 工具调用经 {@link ContainerRestClient} 转发。容器生命周期（首用拉起 + 防重拉 + 闲置回收 + 关闭销毁）
 * 全部委托 {@code docker} 模块的 {@link ContainerManager}；工具清单由子类以静态冒烟数据声明（镜像由我们
 * pin，工具集与所 pin 镜像版本绑定，ADR-0011 决策 5）。</p>
 *
 * <p>优雅降级（#37 验收，#50 注册/启用分离）：docker 运行时未启用（{@code docker.enabled=false}，
 * ContainerManager bean 不存在）或镜像清单缺失本源规格时 {@link #isEnabled()} 返回 {@code false}，
 * 源未启用（已注册、目录列出 enabled=false、工具为空）、不拖垮 hub；工具调用失败由工具层返回
 * 友好文本（见 {@code MarkitdownTools} / {@code JinaTools}）。</p>
 */
public abstract class ContainerMcp implements McpEndpointProvider {

	private static final Logger log = LoggerFactory.getLogger(ContainerMcp.class);

	private final ContainerManager containerManager;
	private final ContainerSpecReader specReader;
	private final ContainerMcpClient client;
	private final ContainerRestClient restClient;

	protected ContainerMcp(ContainerManager containerManager, ContainerSpecReader specReader,
			ContainerEndpoint endpoint) {
		this.containerManager = containerManager;
		this.specReader = specReader;
		// client 持有 containerManager：重试 initialize 期间 touch 容器，防短 TTL 回收误删重试中的容器
		this.client = new ContainerMcpClient(endpoint, containerManager);
		this.restClient = new ContainerRestClient(endpoint, containerManager);
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

	/** 本源接入协议（{@code mcp} 转发容器内 MCP 工具 / {@code rest} 薄包装容器 REST API）。 */
	protected abstract Protocol protocol();

	/** 静态冒烟工具清单（子类声明，镜像 pin 绑定）。 */
	@Override
	public abstract List<ToolCallback> getTools();

	@Override
	public boolean isEnabled() {
		if (containerManager == null) {
			log.debug("docker 容器运行时未启用（docker.enabled=false），ContainerMcp 源 {} 未启用", getName());
			return false;
		}
		return findSpec().isPresent();
	}

	/** 本源对应容器规格（isEnabled 已保证存在；调用路径缺失时抛异常说明，正常不应发生）。 */
	protected ContainerSpec requireSpec() {
		return findSpec().orElseThrow(() -> new IllegalStateException("清单缺少 " + getName()
			+ " 的 " + protocol().name().toLowerCase() + " 型容器规格（manifests/mcp-images.yaml 是否已由 mvn verify 生成）"));
	}

	/** 本源容器规格（镜像清单按源名取，按 {@link #protocol()} 过滤）。 */
	private Optional<ContainerSpec> findSpec() {
		return specReader.byName(getName()).filter(spec -> spec.protocol() == protocol());
	}

	/** MCP 转发客户端（protocol=mcp 型；内部持有 containerManager：首用拉起 + 重试 touch）。 */
	protected ContainerMcpClient client() {
		return client;
	}

	/** REST 转发客户端（protocol=rest 型；内部持有 containerManager：首用拉起）。 */
	protected ContainerRestClient restClient() {
		return restClient;
	}
}
