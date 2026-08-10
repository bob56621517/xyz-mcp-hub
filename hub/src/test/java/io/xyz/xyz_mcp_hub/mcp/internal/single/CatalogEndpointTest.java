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
 * 与 {@code base}（组合源）的序列化映射。
 *
 * <p>集成测试（{@code McpCatalogEndpointTest}）只覆盖当前注册源（native，protocol/base 均为 null）；
 * 本类补齐 container / composite / host 三个非空路径，保证目录 schema 的非空序列化一次定稿
 * （#35/#36 迁入容器/组合源后无需改映射）。无外部依赖（不启动 Spring 上下文）。</p>
 */
class CatalogEndpointTest {

	/** 假 provider（map 只用其名下的目录元数据，provider 本身不被读取）。 */
	private static final McpEndpointProvider FAKE = new McpEndpointProvider() {
		@Override
		public String getName() {
			return "fake";
		}

		@Override
		public String getPath() {
			return "/fake";
		}

		@Override
		public Scope getScope() {
			return Scope.NETWORK;
		}
	};

	@Test
	void containerSourceMapsProtocolToLowercase() {
		var source = new McpSourceRegistry.McpSource("jina", SourceType.CONTAINER, Protocol.REST,
				Scope.NETWORK, FAKE, List.of(), null);
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("container");
		assertThat(dto.protocol()).isEqualTo("rest");
		assertThat(dto.scope()).isEqualTo("network");
		assertThat(dto.base()).isNull();
		assertThat(dto.tools()).isEmpty();
	}

	@Test
	void compositeSourceMapsBaseTracing() {
		var source = new McpSourceRegistry.McpSource("github-readonly", SourceType.COMPOSITE, null,
				Scope.NETWORK, FAKE, List.of(),
				new CompositeBase(List.of("github"), List.of("github_create_issue")));
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("composite");
		assertThat(dto.protocol()).isNull();
		assertThat(dto.base()).isNotNull();
		assertThat(dto.base().includes()).containsExactly("github");
		assertThat(dto.base().excludes()).containsExactly("github_create_issue");
	}

	@Test
	void hostSourceMapsHostScope() {
		var source = new McpSourceRegistry.McpSource("files", SourceType.HOST, null,
				Scope.HOST, FAKE, List.of(), null);
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("host");
		assertThat(dto.scope()).isEqualTo("host");
		assertThat(dto.protocol()).isNull();
	}

}
