package io.xyz.xyz_mcp_hub.mcp.internal.containermcp;

import io.xyz.xyz_mcp_hub.docker.ContainerSpec;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard;
import io.xyz.xyz_mcp_hub.security.SsrUrlGuard.SsrGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * jina 容器源的静态冒烟工具集（#38，rest 型，镜像由我们 pin，工具清单与所 pin 镜像版本绑定，
 * ADR-0011 决策 5）：JVM 薄包装 jina reader 容器 REST API 为 {@code reader} 工具（暴露名
 * {@code jina_reader}）。
 *
 * <p>调用路径（首用拉起）：{@code ContainerManager.ensureRunning} 拉起/复用容器 →
 * {@link ContainerRestClient} POST {@code {"url": ...}} 到容器根路径 → 返回 markdown。jina 只承接
 * {@code http(s)://} 网页/PDF（fetch 退役后的网页能力由 jina 承接），全部 URL 调用
 * {@link SsrUrlGuard#check} 静态预检（ADR-0010 决策 2：容器代抓预检 + 容器隔离兜底）。</p>
 *
 * <p>降级：#38 验收——容器启动失败 / 上游不可达时返回友好文本，不拖垮 hub；docker 运行时缺失 /
 * 清单缺本源 rest 规格时源 {@code isEnabled=false} 不入目录。</p>
 */
public class JinaTools {

	private static final Logger log = LoggerFactory.getLogger(JinaTools.class);

	/** 工具名（源前缀后暴露为 {@code jina_reader}）。 */
	static final String TOOL_NAME = "reader";

	private final ContainerMcp provider;
	private final SsrUrlGuard ssrUrlGuard;

	public JinaTools(ContainerMcp provider, SsrUrlGuard ssrUrlGuard) {
		this.provider = provider;
		this.ssrUrlGuard = ssrUrlGuard;
	}

	@Tool(name = TOOL_NAME, description = """
			将网页或 PDF（url）转换为 Markdown 正文返回。url 支持 http(s)://（jina 容器代抓，含 PDF）。
			例：url=https://example.com 返回该网页的 Markdown；url=https://example.com/report.pdf 返回 PDF 提取文本。
			""")
	public String reader(@ToolParam(description = "要读取的网页/PDF 地址（http/https）") String url) {
		if (url == null || url.isBlank()) {
			return "jina_reader 需要 url 参数（http/https 网页或 PDF 地址）";
		}
		try {
			ssrUrlGuard.check(url);
		}
		catch (SsrGuardException e) {
			return "SSRF 防护拦截：" + e.getMessage();
		}
		try {
			ContainerSpec spec = provider.requireSpec();
			// POST {"url": ...} 到容器根路径：URL 作为路径会遭 query 分隔符歧义，body 传参最稳（官方 API 支持）
			return provider.restClient().postJson(spec, "", requestBody(url));
		}
		catch (RuntimeException e) {
			log.warn("jina 容器调用失败（url={}）：{}", url, e.getMessage());
			return "jina 容器不可用：" + e.getMessage();
		}
	}

	/** JSON 请求体（url 内嵌引号/反斜杠需转义）。 */
	private static String requestBody(String url) {
		return "{\"url\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}
}
