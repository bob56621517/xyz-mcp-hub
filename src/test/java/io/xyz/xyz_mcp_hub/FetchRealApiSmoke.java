package io.xyz.xyz_mcp_hub;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchHttpClient;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchTools;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.FetchService;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.HtmlToMarkdown;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch.PdfTextExtractor;

/**
 * Fetch 端点真实抓取冒烟（手工运行，非自动测试）。
 *
 * <p>验证 {@code /mcp/builtin/fetch} 快路径在真实公网下的行为：HTML→markdown、raw、分块、
 * SSRF 拦截、PDF 管线。步骤化 stdout 输出供 issue 留证。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.FetchRealApiSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web 需真实外部网络（example.com、www.w3.org）
 */
public class FetchRealApiSmoke {

	private static final String EXAMPLE_URL = "https://example.com/";
	private static final String PDF_URL =
		"https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";

	public static void main(String[] args) {
		FetchHttpClient http = new FetchHttpClient();
		try {
			FetchService service = new FetchService(http, new HtmlToMarkdown(), new PdfTextExtractor());
			FetchTools tools = new FetchTools(service);

			System.out.println("[1/5] 抓取 HTML 并转 Markdown：" + EXAMPLE_URL);
			String md = tools.fetch(EXAMPLE_URL, 2000, 0, false);
			System.out.println("      结果：\n" + truncate(md, 400));

			System.out.println("[2/5] raw=true 返回原始内容");
			String raw = tools.fetch(EXAMPLE_URL, 500, 0, true);
			System.out.println("      是否含 HTML 标记：" + raw.toLowerCase().contains("<html"));

			System.out.println("[3/5] 分块读取：max_length=200, start_index=100");
			String chunked = tools.fetch(EXAMPLE_URL, 200, 100, false);
			System.out.println("      结果：\n" + truncate(chunked, 260));

			System.out.println("[4/5] SSRF 拦截：http://127.0.0.1:1/");
			String blocked = tools.fetch("http://127.0.0.1:1/", null, null, null);
			System.out.println("      结果：" + blocked);

			System.out.println("[5/5] PDF 管线：" + PDF_URL);
			String pdfText = tools.fetch(PDF_URL, 2000, 0, false);
			System.out.println("      结果：\n" + truncate(pdfText, 400));

			boolean ok = md.contains("Example Domain")
					&& raw.toLowerCase().contains("<html")
					&& blocked.contains("SSRF 防护拦截")
					&& !pdfText.contains("暂不支持")
					&& pdfText.strip().length() > 0;
			System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）"));
		}
		finally {
			http.close();
		}
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
