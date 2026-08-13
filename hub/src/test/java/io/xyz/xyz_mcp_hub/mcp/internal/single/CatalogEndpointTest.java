package io.xyz.xyz_mcp_hub.mcp.internal.single;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录条目映射纯单测（issue #34，ADR-0016 去 protocol）：验证 {@link CatalogEndpoint#map} 对
 * native/host-scope 的序列化映射。容器型已溶解（ADR-0016），目录不再有 protocol 字段。
 *
 * <p>集成测试（{@code McpCatalogEndpointTest}）覆盖当前注册源；本类补齐 host-scope 非空路径，保证
 * 目录 schema 一次定稿。组合源已整体移除（#49）；host 并入 native（#50），scope 表达部署。
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
	void nativeSourceMapsToLowercase() {
		var source = new McpSourceRegistry.McpSource("jina", SourceType.NATIVE,
				Scope.NETWORK, true, FAKE, List.of());
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("native");
		assertThat(dto.scope()).isEqualTo("network");
		assertThat(dto.enabled()).isTrue();
		assertThat(dto.tools()).isEmpty();
	}

	@Test
	void hostScopedSourceIsNativeTypeWithHostScope() {
		// host 并入 native（#50）：type=native、scope=host 表达部署
		var source = new McpSourceRegistry.McpSource("files", SourceType.NATIVE,
				Scope.HOST, false, FAKE, List.of());
		var dto = CatalogEndpoint.map(source);
		assertThat(dto.type()).isEqualTo("native");
		assertThat(dto.scope()).isEqualTo("host");
		assertThat(dto.enabled()).isFalse();
	}

	@Test
	void disabledSourceCarriesEnabledFalse() {
		var source = new McpSourceRegistry.McpSource("bocha", SourceType.NATIVE,
				Scope.NETWORK, false, FAKE, List.of());
		assertThat(CatalogEndpoint.map(source).enabled()).isFalse();
	}

}
