package io.xyz.xyz_mcp_hub.jina;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.xyz.xyz_mcp_hub.docker.ContainerEndpoint;
import io.xyz.xyz_mcp_hub.docker.ContainerManager;
import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.docker.ContainerSpecReader;
import io.xyz.xyz_mcp_hub.docker.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jina 顶级模块的纯能力 Reader（#53 提升顶级模块，与 docker/playwright 同级）：把网页/PDF（http(s)）或
 * 本地文件（file://）解析为 Markdown 正文。零 MCP/Spring AI 依赖。
 *
 * <p>http(s) URL：复用 {@code docker} 模块的 {@link ContainerManager} 首用拉起 jina reader 容器
 * （清单 {@code manifests/mcp-images.yaml} 的 rest 规格），经 {@link JinaRestClient} POST
 * {@code {"url":...}} 到容器根路径代抓，返回 markdown。file:// 本地文件：直接读取宿主文件内容返回
 * （#53 验收：能力测试非冒烟、不触网、不依赖容器——本地解析不启动容器）。</p>
 *
 * <p>优雅降级：docker 运行时缺失（{@code docker.enabled=false}，containerManager 为 null）或清单缺本源
 * rest 规格时 {@link #isAvailable()} 返回 {@code false}（源未启用、目录列出 enabled=false，见
 * {@code JinaTools}）。源级 enabled 由 docker 可用性（http(s) 主要能力）门控；file:// 本地解析虽不启动
 * 容器，仍随源整体启用/未启用（docker 禁用时源工具不暴露）。</p>
 *
 * <p><strong>能力边界</strong>：file:// 读取宿主文件系统（与 HostMcp 同类语义）。调用方（MCP 工具层）负责
 * 访问策略与 scheme 路由；http(s) 走 SSRF 预检（ADR-0010），file:// 不做 SSRF（本地读取非网络代抓）。</p>
 */
public class JinaReader {

	private static final Logger log = LoggerFactory.getLogger(JinaReader.class);

	/** 源名（目录源名与清单 jina 节点）。 */
	static final String SOURCE_NAME = "jina";

	private final ContainerManager containerManager;
	private final ContainerSpecReader specReader;
	private final JinaRestClient restClient;

	public JinaReader(ContainerManager containerManager, ContainerSpecReader specReader, ContainerEndpoint endpoint) {
		this.containerManager = containerManager;
		this.specReader = specReader;
		this.restClient = new JinaRestClient(endpoint, containerManager);
	}

	/** 源是否可用：docker 运行时存在且清单含本源 rest 规格（http(s) 代抓路径的前置）。 */
	public boolean isAvailable() {
		return containerManager != null && findSpec().isPresent();
	}

	/** http(s) 网页/PDF 代抓：首用拉起 jina reader 容器 → POST url → 返回 markdown。 */
	public String readUrl(String url) {
		ContainerSpec spec = requireSpec();
		return restClient.postJson(spec, "", requestBody(url));
	}

	/** file:// 本地文件解析：直接读取宿主文件内容返回（不依赖容器）。 */
	public String readLocalFile(String fileUri) {
		URI uri;
		try {
			uri = URI.create(fileUri);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("非法 file:// 路径：" + fileUri, e);
		}
		if (uri.getScheme() == null || !"file".equalsIgnoreCase(uri.getScheme()) || uri.getHost() != null) {
			throw new IllegalArgumentException("仅支持本地 file:// 路径（无主机）：" + fileUri);
		}
		Path path = Path.of(uri);
		try {
			return Files.readString(path);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取本地文件失败：" + path, e);
		}
	}

	/** 本源容器规格（isAvailable 已保证存在；调用路径缺失时抛异常说明，正常不应发生）。 */
	private ContainerSpec requireSpec() {
		return findSpec().orElseThrow(() -> new IllegalStateException("清单缺少 " + SOURCE_NAME
			+ " 的 rest 型容器规格（manifests/mcp-images.yaml 是否已由 mvn verify 生成）"));
	}

	/** 本源容器规格（镜像清单按源名取，按 rest 型过滤）。 */
	private Optional<ContainerSpec> findSpec() {
		return specReader.byName(SOURCE_NAME).filter(spec -> spec.protocol() == Protocol.REST);
	}

	/** JSON 请求体（url 内嵌引号/反斜杠需转义）。 */
	private static String requestBody(String url) {
		return "{\"url\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}
}
