package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import io.xyz.xyz_mcp_hub.docker.ContainerHandle;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.DockerProperties;
import io.xyz.xyz_mcp_hub.docker.internal.DockerCliOps;
import org.springframework.ai.tool.ToolCallback;

/**
 * 真实 docker 冒烟（手工运行，非自动测试）——#38 验收：首用拉起 jina 容器 + 真实 REST 转发
 * {@code jina_reader}（网页/PDF→markdown）+ 容器绑 127.0.0.1 / 隔离网络 + SSRF 预检 + 闲置回收。
 *
 * <p>运行：{@code ./mvnw exec:java -pl hub -Dexec.mainClass=io.xyz.xyz_mcp_hub.mcp.internal.containermcp.JinaContainerMcpSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}
 * （在仓库根目录执行，保证默认 manifest-path 指向生成的 manifests/mcp-images.yaml；须加 {@code -pl hub}）。</p>
 *
 * @requires-docker 需本机 docker daemon 可用（docker info 通过）；jina 镜像
 * {@code ghcr.io/jina-ai/reader:latest} 首用拉起时自动 pull（防重拉，镜像大，首次拉取耗时）；
 * 清单需 {@code ./mvnw verify} 生成
 * @requires-web 真实公网代抓（example.com）；容器出网不依赖宿主代理，宿主 fake-ip 代理不影响容器
 */
public class JinaContainerMcpSmoke {

	private static final String EXAMPLE_URL = "https://example.com";
	private static final String PRIVATE_URL = "http://127.0.0.1:8080/internal";

	public static void main(String[] args) {
		DockerProperties props = new DockerProperties();
		// 冒烟用短 TTL / 短扫描周期，验证后台闲置回收线程真实回收（生产默认 TTL=600s 过长）
		props.setTtlSeconds(3);
		props.setScanIntervalSeconds(1);
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
		System.out.println("[3/8] 首次调用拉起容器 + 真实 REST 转发 jina_reader（https://example.com）");
		System.out.println("      （容器尚未启动；工具调用经 ContainerRestClient 内部 ensureRunning 首用拉起 + 健康检查 + 自动 pull）");
		JinaContainerMcp provider = new JinaContainerMcp(manager, reader, ContainerEndpoint.hostPort());
		ToolCallback tool = provider.getTools().get(0);
		String md = tool.call("{\"url\":\"" + EXAMPLE_URL + "\"}");
		System.out.println("      返回 Markdown（截断 400 字符）:\n------\n" + truncate(md, 400) + "\n------");
		// @Tool 返回 String 会被 JSON 编码（全库既有行为），用 contains 判定
		boolean converted = md != null && md.contains("Example Domain");
		System.out.println("      受管容器数=" + manager.managedCount() + "（首用拉起后应 1）");
		System.out.println("      判定：" + (converted ? "通过（首次调用拉起容器 + 真实代抓返回 markdown）" : "失败"));

		System.out.println("[4/8] 验收：容器绑 127.0.0.1 + 隔离网络（docker inspect）");
		String cid = manager.managed().stream().map(ContainerHandle::containerId).findFirst().orElse("无");
		String portBindings = dockerInspect(cid, "{{json .HostConfig.PortBindings}}");
		String networks = dockerInspect(cid, "{{json .NetworkSettings.Networks}}");
		System.out.println("      PortBindings: " + portBindings);
		System.out.println("      Networks:     " + networks);
		boolean bound = portBindings.contains("127.0.0.1") && networks.contains("xyz-mcp-hub");
		System.out.println("      判定：" + (bound ? "通过（绑 127.0.0.1 + 隔离网络 xyz-mcp-hub）" : "失败"));

		System.out.println("[5/8] 验收：SSRF 预检拦截内网地址（http(s) url 交给容器前）");
		String guarded = tool.call("{\"url\":\"" + PRIVATE_URL + "\"}");
		System.out.println("      结果: " + guarded);
		boolean ssrf = guarded.contains("SSRF 防护拦截");
		System.out.println("      判定：" + (ssrf ? "通过（内网地址被拒）" : "失败"));

		System.out.println("[6/8] 验收：非 http(s) url 被 scheme 白名单拦截（jina 只承接网页/PDF）");
		String fileUrl = tool.call("{\"url\":\"file:///etc/hosts\"}");
		System.out.println("      结果: " + fileUrl);
		boolean scheme = fileUrl.contains("SSRF 防护拦截");
		System.out.println("      判定：" + (scheme ? "通过（file:// 被 scheme 白名单拒）" : "失败"));

		System.out.println("[7/8] 验收：闲置回收（后台扫描线程，TTL=" + props.getTtlSeconds() + "s / 扫描="
			+ props.getScanIntervalSeconds() + "s，等待后台回收）");
		sleepSeconds(props.getTtlSeconds() + props.getScanIntervalSeconds() + 4);
		boolean reclaimed = manager.managedCount() == 0;
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 0，容器已被后台回收线程移除）");
		System.out.println("      判定：" + (reclaimed ? "通过（闲置容器被回收）" : "失败"));

		System.out.println("[8/8] 关闭销毁：重新拉起后 destroy");
		manager.ensureRunning(jina);
		manager.destroy();
		boolean destroyed = manager.managedCount() == 0;
		System.out.println("      受管容器数=" + manager.managedCount() + "（应 0，已关闭销毁）");
		System.out.println("      判定：" + (destroyed ? "通过（关闭销毁）" : "失败"));

		boolean ok = converted && bound && ssrf && scheme && reclaimed && destroyed;
		System.out.println("结论：" + (ok ? "通过（首用拉起 + REST 转发 + 网络隔离 + SSRF 预检 + 闲置回收 全部可用）"
			: "未通过（见上方输出）"));
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
