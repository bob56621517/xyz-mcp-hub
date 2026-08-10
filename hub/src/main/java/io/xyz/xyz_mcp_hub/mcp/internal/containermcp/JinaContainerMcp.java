package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.util.List;

import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * jina 容器 MCP 源（#38，protocol=rest 型）：read manifest → 首用拉起容器 → JVM 薄包装其 REST API
 * 为 {@code jina_reader} 工具。
 *
 * <p>静态冒烟工具清单由 {@link JinaTools} 代码声明（镜像由我们 pin）；容器生命周期由
 * {@code docker} 模块的 {@link ContainerManager} 管理；端点解析默认经 {@link ContainerEndpoint#restUrl()}
 * （容器绑 127.0.0.1 + 隔离网络，ADR-0010/0012）。转发前经 {@link SsrUrlGuard#check} 静态预检
 * （ADR-0010 决策 2：容器代抓预检 + 容器隔离兜底）。</p>
 *
 * <p>优雅降级：docker 运行时未启用（{@code docker.enabled=false}，ContainerManager bean 缺失）或清单
 * 缺失本源规格时 {@link #isEnabled()} 返回 {@code false}，源不注册；{@link ContainerManager} 与
 * {@link ContainerEndpoint} 经 {@link ObjectProvider} 注入以支持缺省。</p>
 */
@Component
public class JinaContainerMcp extends ContainerMcp {

	private final List<ToolCallback> tools;

	@Autowired
	public JinaContainerMcp(ObjectProvider<ContainerManager> containerManagerProvider,
			ContainerSpecReader specReader, ObjectProvider<ContainerEndpoint> endpointProvider) {
		this(containerManagerProvider.getIfAvailable(() -> null), specReader,
			endpointProvider.getIfAvailable(ContainerEndpoint::hostPort));
	}

	/** 测试构造：直接注入 plain 依赖（不启 Spring；与 ContainerManager 的测试构造模式一致）。 */
	JinaContainerMcp(ContainerManager containerManager, ContainerSpecReader specReader,
			ContainerEndpoint endpoint) {
		super(containerManager, specReader, endpoint);
		this.tools = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(new JinaTools(this, new SsrUrlGuard()))
			.build()
			.getToolCallbacks());
	}

	@Override
	public String getName() {
		return "jina";
	}

	@Override
	protected Protocol protocol() {
		return Protocol.REST;
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}
}
