package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;

import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录条目映射纯单测（issue #34）：验证 {@link CatalogEndpoint#map} 对非空 {@code protocol}（容器源）
 * 的序列化映射。
 *
 * <p>集成测试（{@code McpCatalogEndpointTest}）只覆盖当前注册源（native，protocol 为 null）；
 * 本类补齐 container / host 两个非空路径，保证目录 schema 的非空序列化一次定稿
 * （#35/#36 迁入容器源后无需改映射）。组合源已整体移除（#49），目录不再有 composite/base。
 * 无外部依赖（不启动 Spring 上下文）。</p>
 */
class CatalogEndpointTest {

	/** 假 provider（map 只用其名下的目录元数据，provider 本身不被读取）。 */
	private static final McpEndpointProvider FAKE = new McpEndpointProvider() {
		@Override
		public String getName() {
			return "fake";
		}

		@Override
		public Scope getScope() {
			return Scope.NETWORK;
		}
	};

	@Test
	void containerSourceMapsProtocolToLowercase() {
		var source = new McpSourceRegistry.McpSource("jina", SourceType.CONTAINER, Protocol.REST,
				Scope.NETWORK, FAKE, List.of());
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("container");
		assertThat(dto.protocol()).isEqualTo("rest");
		assertThat(dto.scope()).isEqualTo("network");
		assertThat(dto.tools()).isEmpty();
	}

	@Test
	void hostSourceMapsHostScope() {
		var source = new McpSourceRegistry.McpSource("files", SourceType.HOST, null,
				Scope.HOST, FAKE, List.of());
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("host");
		assertThat(dto.scope()).isEqualTo("host");
		assertThat(dto.protocol()).isNull();
	}

}
