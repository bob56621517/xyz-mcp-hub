package io.xyz.xyz_mcp_hub;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧多端点移除契约测试（issue #39）：旧多端点路径（{@code /mcp/builtin/*}、{@code /mcp/server/*}、
 * {@code /mcp/config/*}）已干净断掉（无兼容承诺、不做重定向），HTTP 返回 404；Hub 只保留单端点
 * {@code /xyz-hub/mcp} + {@code /xyz-hub/sse} + 目录 {@code /xyz-hub/catalog}。
 *
 * <p>经真实 HTTP 验证旧路径消失（404）与目录仍可用（200）。单端点 {@code /xyz-hub/mcp}/{@code /sse}
 * 的连通与工具视图由 {@code McpSingleEndpointTest} 覆盖（不对 {@code /xyz-hub/sse} 发 GET——SSE
 * 建连 GET 会打开持续事件流阻塞客户端读取，故本类只做旧路径 404 与目录存活性断言）。
 * {@code @DirtiesContext(BEFORE_CLASS)}：本类无自定义动态属性会复用共享 context，隔离共享单端点传输
 * 被套件中其他 context 置为 isClosing 的影响（同 {@code McpUtilsEndpointTest}）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class McpOldEndpointsRemovedTest {

	@LocalServerPort
	private int port;

	private int getStatus(String path) throws Exception {
		HttpClient httpClient = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return response.statusCode();
	}

	// ---- 验收 1：旧多端点路径已移除（404），无残留路由 ----
	// 覆盖旧机制曾注册的全部路径：/mcp/builtin/*（原生/proxy/host/容器源）、/mcp/server/*（旧 server 语义）、
	// /mcp/config/*（旧 Space 组合端点默认命名空间）。

	@Test
	void legacyBuiltinEndpointReturns404() throws Exception {
		assertThat(getStatus("/mcp/builtin/utils")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/bocha")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/playwright")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/context7")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/grep-app")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/wikidata")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/github-full")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/github-readonly")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/containermcp/markitdown")).isEqualTo(404);
		assertThat(getStatus("/mcp/builtin/containermcp/jina")).isEqualTo(404);
	}

	@Test
	void legacyServerEndpointReturns404() throws Exception {
		assertThat(getStatus("/mcp/server/utils")).isEqualTo(404);
		assertThat(getStatus("/mcp/server/bocha")).isEqualTo(404);
		assertThat(getStatus("/mcp/server/github-full")).isEqualTo(404);
	}

	@Test
	void legacyConfigEndpointReturns404() throws Exception {
		assertThat(getStatus("/mcp/config/devops")).isEqualTo(404);
		assertThat(getStatus("/mcp/config/custom")).isEqualTo(404);
		assertThat(getStatus("/mcp/config/orders")).isEqualTo(404);
	}

	// ---- 验收 2：目录仍可用（单端点连通性由 McpSingleEndpointTest 覆盖） ----

	@Test
	void catalogEndpointStillServes200() throws Exception {
		assertThat(getStatus("/xyz-hub/catalog")).isEqualTo(200);
	}

}
