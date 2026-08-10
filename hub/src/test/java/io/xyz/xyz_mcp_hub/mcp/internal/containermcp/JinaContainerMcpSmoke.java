package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.DockerProperties;
import io.xyz.xyz_mcp_hub.docker.internal.DockerCliOps;
import org.springframework.ai.tool.ToolCallback;

/**
 * 真实 docker 冒烟（手工运行，非自动测试）——#38 验收：拉起/复用 jina 容器 + 真实 REST 转发
 * {@code jina_reader}（网页→markdown）+ 容器绑 127.0.0.1 / 隔离网络 + SSRF 预检。
 *
 * <p>就绪等待（#38 冒烟，用户建议）：冒烟先 {@code ContainerManager.ensureRunning} 复用/拉起容器
 * （{@code DockerCliOps} 对已运行同名容器直接复用，不重建），每 10 秒探测一次真实代抓（HTTP 就绪即
 * 容器可用），最多等 5 分钟——覆盖首次 pull + 应用就绪窗口；容器已就绪则直接进入正式测试（快速通过）。
 * 生产代码的 {@code ContainerRestClient} 启动重试为调用层兜底，冒烟的就绪等待是自身前置，两者互补。</p>
 *
 * <p>容器保持运行：冒烟结束时**不销毁容器**（便于复用/后续验证）；闲置回收与关闭销毁由
 * {@code ContainerManagerTest} 单测与 {@code MarkitdownContainerMcpSmoke}（#37）覆盖——jina 与
 * markitdown 共用同一 {@code ContainerManager} 生命周期，本冒烟聚焦 jina rest 特有能力。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -pl hub -Dexec.mainClass=io.xyz.xyz_mcp_hub.mcp.internal.containermcp.JinaContainerMcpSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}
 * （在仓库根目录执行，保证默认 manifest-path 指向生成的 manifests/mcp-images.yaml；须加 {@code -pl hub}；
 * exec:java 默认工作目录是模块 basedir，另需 {@code -Dexec.workingdir=<仓库根>}）。</p>
 *
 * @requires-docker 需本机 docker daemon 可用（docker info 通过）；jina 镜像
 * {@code ghcr.io/jina-ai/reader:latest} 首用拉起时自动 pull（防重拉，镜像大，首次拉取耗时）；
 * 清单需 {@code ./mvnw verify} 生成
 * @requires-web 真实公网代抓（example.com）；容器出网不依赖宿主代理，宿主 fake-ip 代理不影响容器
 */
public class JinaContainerMcpSmoke {

	private static final String EXAMPLE_URL = "https://example.com";
	private static final String PRIVATE_URL = "http://127.0.0.1:8080/internal";

	/** 就绪探测间隔（秒，用户建议每 10s 测一次）。 */
	private static final int READY_PROBE_INTERVAL_S = 10;

	/** 就绪等待上限（毫秒，用户建议最多 5 分钟，覆盖首启 + 首次 pull）。 */
	private static final long READY_WAIT_MS = 5 * 60_000L;

	/** 探测单次请求超时（容器未就绪时快速失败，避免拖慢轮询）。 */
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

	public static void main(String[] args) {
		DockerProperties props = new DockerProperties();
		// 保持默认 TTL（600s）：测试期间不触发后台闲置回收，容器测试后保持运行（不销毁）
		props.setStartTimeoutSeconds(60);
		props.setPullTimeoutSeconds(600);
		String dockerCmd = props.getCommand();

		System.out.println("[1/8] 依赖检查：docker CLI 可用性");
		if (!dockerAvailable(dockerCmd)) {
			System.out.println("      docker 不可用（" + dockerCmd + "），退出");
			return;
		}
		System.out.println("      docker OK");

		System.out.println("[2/8] 读取镜像清单 manifest → jina spec（protocol=rest）");
		ContainerSpecReader reader = new ContainerSpecReader(Path.of(props.getManifestPath()));
		ContainerSpec jina = reader.byName("jina").orElse(null);
		if (jina == null) {
			System.out.println("      清单缺少 jina 节点（manifest 由 mvn verify 生成），退出");
			return;
		}
		System.out.println("      spec: " + jina);

		ContainerManager manager = new ContainerManager(new DockerCliOps(props), props);
		JinaContainerMcp provider = new JinaContainerMcp(manager, reader, ContainerEndpoint.hostPort());
		ToolCallback tool = provider.getTools().get(0);

		System.out.println("[3/8] 就绪等待：复用/拉起容器 + 轮询探测 HTTP 就绪（每 " + READY_PROBE_INTERVAL_S + "s，最多 "
			+ READY_WAIT_MS / 1000 + "s，覆盖首启 + 首次 pull 窗口）");
		long waitStart = System.currentTimeMillis();
		int attempt = 0;
		boolean ready = false;
		while (System.currentTimeMillis() - waitStart < READY_WAIT_MS) {
			attempt++;
			try {
				// 幂等：同名容器已运行则复用（DockerCliOps），未运行则拉起
				manager.ensureRunning(jina);
				if (probeReady("http://127.0.0.1:" + jina.hostPort() + "/")) {
					ready = true;
					break;
				}
			}
			catch (RuntimeException e) {
				System.out.println("      第 " + attempt + " 次：拉起/探测异常（" + e.getMessage() + "）");
			}
			System.out.println("      第 " + attempt + " 次：容器未就绪，" + READY_PROBE_INTERVAL_S + "s 后重试");
			sleepSeconds(READY_PROBE_INTERVAL_S);
		}
		if (!ready) {
			System.out.println("      判定：失败（" + READY_WAIT_MS / 1000 + "s 内容器未就绪，退出）");
			manager.destroy();
			return;
		}
		System.out.println("      判定：通过（容器就绪，耗时 " + (System.currentTimeMillis() - waitStart) / 1000 + "s）");

		System.out.println("[4/8] 真实 REST 转发 jina_reader（https://example.com → markdown）");
		String md = tool.call("{\"url\":\"" + EXAMPLE_URL + "\"}");
		System.out.println("      返回 Markdown（截断 400 字符）:\n------\n" + truncate(md, 400) + "\n------");
		// @Tool 返回 String 会被 JSON 编码（全库既有行为），用 contains 判定
		boolean converted = md != null && md.contains("Example Domain");
		System.out.println("      受管容器数=" + manager.managedCount() + "（应为 1）");
		System.out.println("      判定：" + (converted ? "通过（真实代抓返回 markdown）" : "失败"));

		System.out.println("[5/8] 验收：容器绑 127.0.0.1 + 隔离网络（docker inspect）");
		String cid = manager.managed().stream().map(ContainerHandle::containerId).findFirst().orElse("无");
		String portBindings = dockerInspect(cid, "{{json .HostConfig.PortBindings}}");
		String networks = dockerInspect(cid, "{{json .NetworkSettings.Networks}}");
		System.out.println("      PortBindings: " + portBindings);
		System.out.println("      Networks:     " + networks);
		boolean bound = portBindings.contains("127.0.0.1") && networks.contains("xyz-mcp-hub");
		System.out.println("      判定：" + (bound ? "通过（绑 127.0.0.1 + 隔离网络 xyz-mcp-hub）" : "失败"));

		System.out.println("[6/8] 验收：SSRF 预检拦截内网地址（http(s) url 交给容器前）");
		String guarded = tool.call("{\"url\":\"" + PRIVATE_URL + "\"}");
		System.out.println("      结果: " + guarded);
		boolean ssrf = guarded.contains("SSRF 防护拦截");
		System.out.println("      判定：" + (ssrf ? "通过（内网地址被拒）" : "失败"));

		System.out.println("[7/8] 验收：非 http(s) url 被 scheme 白名单拦截（jina 只承接网页/PDF）");
		String fileUrl = tool.call("{\"url\":\"file:///etc/hosts\"}");
		System.out.println("      结果: " + fileUrl);
		boolean scheme = fileUrl.contains("SSRF 防护拦截");
		System.out.println("      判定：" + (scheme ? "通过（file:// 被 scheme 白名单拒）" : "失败"));

		System.out.println("[8/8] 验收：容器保持运行（冒烟不销毁，便于复用；闲置回收/销毁由 ContainerManagerTest 单测与 markitdown 冒烟覆盖）");
		boolean kept = manager.managedCount() >= 1;
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 ≥1，容器保持运行）");
		System.out.println("      判定：" + (kept ? "通过（容器保持运行）" : "失败"));

		boolean ok = converted && bound && ssrf && scheme && kept;
		System.out.println("结论：" + (ok ? "通过（拉起/复用 + REST 转发 + 网络隔离 + SSRF 预检 全部可用）"
			: "未通过（见上方输出）"));
	}

	/** 就绪探测：POST 真实代抓（example.com），返回 markdown 含 "Example Domain" 即容器 HTTP 就绪。 */
	private static boolean probeReady(String baseUrl) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
				.timeout(PROBE_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + EXAMPLE_URL + "\"}",
					StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response =
				client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return response.statusCode() < 400 && response.body() != null && response.body().contains("Example Domain");
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static String truncate(String text, int limit) {
		if (text == null) {
			return "null";
		}
		return text.length() <= limit ? text : text.substring(0, limit) + "…";
	}

	private static void sleepSeconds(long seconds) {
		try {
			Thread.sleep(seconds * 1_000L);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean dockerAvailable(String dockerCmd) {
		return run(dockerCmd, "info").exitCode == 0;
	}

	private static String dockerInspect(String containerId, String format) {
		return run("docker", "inspect", containerId, "--format", format).output;
	}

	private static ExecResult run(String... tokens) {
		try {
			Process process = new ProcessBuilder(tokens).redirectErrorStream(true).start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int code = process.waitFor();
			return new ExecResult(code, out);
		}
		catch (IOException | InterruptedException e) {
			return new ExecResult(-1, e.getMessage());
		}
	}

	private record ExecResult(int exitCode, String output) {
	}
}
