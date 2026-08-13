package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.jina;

import java.util.List;

import io.xyz.xyz_mcp_hub.jina.JinaReader;
import io.xyz.xyz_mcp_hub.mcp.McpEndpointProvider;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.SsrGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * jina 源的工具类即源（#53 模式；ADR-0016 native 化）：{@link JinaTools} 本身实现
 * {@link McpEndpointProvider}——{@code @Tool} 方法 + 源元数据（name/scope/enabled）合一，纯能力由顶级
 * 模块 {@link JinaReader} 承担。可 {@code new JinaTools(...)} 直接调用 {@code @Tool} 方法测试。
 *
 * <p>源：name=jina，scope=NETWORK，type 默认 {@code NATIVE}（包装 HTTP API，端点配置化，ADR-0016；
 * 原 container 型已溶解）。工具 {@code reader} 支持两种输入：{@code http(s)://} 网页/PDF（容器代抓，
 * SSRF 预检后 {@link JinaReader#readUrl}，ADR-0010）与 {@code file://} 本地文件（{@link JinaReader#readLocalFile}
 * ——hub 宿主文件 → multipart 上传，坐标见 ADR-0016）。</p>
 *
 * <p>降级：{@code jina.url} 未配置时 {@link #isEnabled()} 返回 {@code false}（源已注册、目录列出
 * {@code enabled=false}、工具为空，#50）；端点未起/上游不可达时工具调用返回友好文本，不拖垮 hub。</p>
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
	public JinaTools(JinaReader jinaReader) {
		this(jinaReader, new SsrUrlGuard());
	}

	/** 测试构造：注入 fake reader（SsrUrlGuard 用真实实现，验证拦截）。 */
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
	public boolean isEnabled() {
		return jinaReader != null && jinaReader.isAvailable();
	}

	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

	@Tool(name = TOOL_NAME, description = """
			将网页、PDF 或本地文件（url）转换为 Markdown 正文返回。url 支持 http(s)://（jina 容器代抓，含 PDF）
			与 file://（hub 宿主本地文件，经上传转换，支持 pdf/docx/xlsx/pptx 及文本）。例：url=https://example.com
			返回该网页的 Markdown；url=file:///tmp/doc.pdf 返回该本地文件的 Markdown。
			""")
	public String reader(@ToolParam(description = "要读取的网页/PDF 或本地文件地址（http/https/file）") String url) {
		if (url == null || url.isBlank()) {
			return "jina_reader 需要 url 参数（http/https 网页/PDF 或 file:// 本地文件）";
		}
		if (!isEnabled()) {
			return "jina 源不可用（jina.url 未配置，见 ADR-0016）";
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
				log.warn("jina 代抓失败（url={}）：{}", url, e.getMessage());
				return "jina 不可用：" + e.getMessage();
			}
		}
		if (isFileUri(url)) {
			try {
				return jinaReader.readLocalFile(url);
			}
			catch (RuntimeException e) {
				log.warn("jina 本地文件转换失败（url={}）：{}", url, e.getMessage());
				return "本地文件转换失败：" + e.getMessage();
			}
		}
		return "不支持的 url scheme（仅 http/https/file）：" + url;
	}

	/** url 是否为 http(s) 网络代抓（只有这类才走 SSRF 预检）。 */
	private static boolean isHttpUri(String url) {
		String lower = url.toLowerCase();
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	/** url 是否为 file:// 本地文件（hub 宿主文件，上传转换，不做 SSRF——本地读取非网络代抓）。 */
	private static boolean isFileUri(String url) {
		return url.toLowerCase().startsWith("file://");
	}
}
