package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import java.util.List;

import io.xyz.xyz_mcp_hub.docker.Protocol;
import io.xyz.xyz_mcp_hub.jina.JinaReader;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.SsrGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * jina 源的工具类即源（#53）：{@link JinaTools} 本身实现 {@link McpEndpointProvider}——{@code @Tool}
 * 方法 + 源元数据（getName/scope/type/protocol/enabled）合一，纯能力由顶级模块 {@link JinaReader} 承担。
 * 可 {@code new JinaTools(...)} 直接调用 {@code @Tool} 方法测试（不再有 {@code JinaContainerMcp} 包装类）。
 *
 * <p>源：name=jina，scope=NETWORK，type=CONTAINER（protocol=rest，能力来自 jina reader 容器）。工具
 * {@code reader} 支持两种输入：{@code http(s)://} 网页/PDF（容器代抓，SSRF 预检后
 * {@link JinaReader#readUrl}，ADR-0010）与 {@code file://} 本地文件（{@link JinaReader#readLocalFile}，
 * #53 新增，本地解析不依赖容器）。</p>
 *
 * <p>降级：docker 运行时缺失（{@code docker.enabled=false}）/清单缺本源 rest 规格时
 * {@link #isEnabled()} 返回 {@code false}（源已注册、目录列出 enabled=false、工具为空，#50）；
 * 容器启动失败 / 上游不可达时工具调用返回友好文本，不拖垮 hub。</p>
 */
@Component
public class JinaTools implements McpEndpointProvider {

	private static final Logger log = LoggerFactory.getLogger(JinaTools.class);

	/** 工具名（源前缀后暴露为 {@code jina_reader}）。 */
	static final String TOOL_NAME = "reader";

	private final JinaReader jinaReader;
	private final SsrUrlGuard ssrUrlGuard;
	private final List<ToolCallback> tools;

	@Autowired
	public JinaTools(ObjectProvider<JinaReader> jinaReaderProvider) {
		this(jinaReaderProvider.getIfAvailable(() -> null), new SsrUrlGuard());
	}

	/** 测试构造：直接注入 plain 依赖（不启 Spring；与 ContainerManager 的测试构造模式一致）。 */
	JinaTools(JinaReader jinaReader, SsrUrlGuard ssrUrlGuard) {
		this.jinaReader = jinaReader;
		this.ssrUrlGuard = ssrUrlGuard;
		this.tools = List.of(MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks());
	}

	@Override
	public String getName() {
		return "jina";
	}

	@Override
	public Scope getScope() {
		return Scope.NETWORK;
	}

	@Override
	public SourceType getSourceType() {
		return SourceType.CONTAINER;
	}

	@Override
	public Protocol getProtocol() {
		return Protocol.REST;
	}

	@Override
	public boolean isEnabled() {
		return jinaReader != null && jinaReader.isAvailable();
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

	@Tool(name = TOOL_NAME, description = """
			将网页、PDF 或本地文件（url）转换为 Markdown 正文返回。url 支持 http(s)://（jina 容器代抓，含 PDF）
			与 file://（本地文件解析）。例：url=https://example.com 返回该网页的 Markdown；
			url=file:///tmp/doc.md 返回该本地文件内容。
			""")
	public String reader(@ToolParam(description = "要读取的网页/PDF 或本地文件地址（http/https/file）") String url) {
		if (url == null || url.isBlank()) {
			return "jina_reader 需要 url 参数（http/https 网页/PDF 或 file:// 本地文件）";
		}
		if (jinaReader == null) {
			return "jina 源不可用（docker 运行时缺失或清单缺 rest 规格）";
		}
		if (isHttpUri(url)) {
			try {
				ssrUrlGuard.check(url);
			}
			catch (SsrGuardException e) {
				return "SSRF 防护拦截：" + e.getMessage();
			}
			try {
				return jinaReader.readUrl(url);
			}
			catch (RuntimeException e) {
				log.warn("jina 容器调用失败（url={}）：{}", url, e.getMessage());
				return "jina 容器不可用：" + e.getMessage();
			}
		}
		if (isFileUri(url)) {
			try {
				return jinaReader.readLocalFile(url);
			}
			catch (RuntimeException e) {
				log.warn("jina 本地文件解析失败（url={}）：{}", url, e.getMessage());
				return "本地文件解析失败：" + e.getMessage();
			}
		}
		return "不支持的 url scheme（仅 http/https/file）：" + url;
	}

	/** url 是否为 http(s) 网络代抓（只有这类才走 SSRF 预检）。 */
	private static boolean isHttpUri(String url) {
		String lower = url.toLowerCase();
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	/** url 是否为 file:// 本地文件（本地解析，不做 SSRF——本地读取非网络代抓）。 */
	private static boolean isFileUri(String url) {
		return url.toLowerCase().startsWith("file://");
	}
}
