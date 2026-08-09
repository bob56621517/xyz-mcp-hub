package io.xyz.xyz_mcp_hub.content;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * markitdown 转换链路冒烟（手工运行，非自动测试）。
 *
 * <p>验证 {@link MarkitdownServer} 拉起子进程 + 健康检查 + {@link ConvertEngine} 经
 * markitdown 把 fixture bytes 转 Markdown + 关闭销毁，全链路步骤化 stdout 输出供 issue
 * 留证。步骤 [1/5] 前依赖本机安装 uv（{@code uvx} 可用）与可访问 PyPI（首次下载依赖）。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.content.MarkitdownSmoke -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-service markitdown 需本机可用 uvx 拉取 markitdown-mcp 并监听端口（命令自动拉起）
 * @requires-web 首次需访问 PyPI 下载依赖（后续命中本地缓存）
 */
public class MarkitdownSmoke {

	private static final int PORT = 39001;

	public static void main(String[] args) throws Exception {
		if (!commandAvailable("uvx")) {
			System.out.println("[环境] 未设置，退出：未找到 uvx（uv），请先安装 uv：https://docs.astral.sh/uv/");
			return;
		}
		MarkitdownProperties props = new MarkitdownProperties();
		props.setEnabled(true);
		props.setPort(PORT);
		props.setCommand("uvx --with mcp<2.0.0 markitdown-mcp --http --port " + PORT + " --host localhost");
		MarkitdownServer server = new MarkitdownServer(props);
		MarkitdownFormatConverter converter = null;
		try {
			server.ensureStarted();
			System.out.println("[1/5] markitdown 子进程拉起并通过健康检查，端点：" + server.endpointUrl());

			converter = new MarkitdownFormatConverter(server);
			ConvertEngine engine = new ConvertEngine(List.of(converter));

			System.out.println("[2/5] HTML → Markdown（fixtures/markitdown/sample.html）");
			String html = engine.convert(load("/fixtures/markitdown/sample.html"), "html");
			System.out.println("      结果：\n" + truncate(html, 300));
			boolean htmlOk = html.contains("Markitdown 冒烟样例");

			System.out.println("[3/5] Markdown → Markdown（fixtures/markitdown/sample.md）");
			String md = engine.convert(load("/fixtures/markitdown/sample.md"), "md");
			System.out.println("      结果：\n" + truncate(md, 200));
			boolean mdOk = md.contains("标题一");

			System.out.println("[4/5] 纯文本 → Markdown（fixtures/markitdown/sample.txt）");
			String txt = engine.convert(load("/fixtures/markitdown/sample.txt"), "txt");
			System.out.println("      结果：\n" + truncate(txt, 200));
			boolean txtOk = txt.contains("纯文本内容");

			System.out.println("[5/5] 转换格式覆盖：" + converter.supportedFormats().size() + " 种"
					+ "（含 pdf / docx / xlsx / pptx / epub）");
			boolean scopeOk = converter.supportedFormats().containsAll(List.of("pdf", "docx", "xlsx"));

			boolean ok = htmlOk && mdOk && txtOk && scopeOk;
			System.out.println("结论：" + (ok ? "通过（结果合理）" : "未通过（见上方输出）"));
		}
		finally {
			if (converter != null) {
				converter.destroy();
			}
			server.destroy();
			System.out.println("markitdown 子进程已销毁");
		}
	}

	private static byte[] load(String path) throws IOException {
		try (InputStream in = MarkitdownSmoke.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("缺少 fixture：" + path);
			}
			return in.readAllBytes();
		}
	}

	private static boolean commandAvailable(String cmd) {
		try {
			Process p = new ProcessBuilder(List.of(cmd, "--version"))
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
			return p.waitFor() == 0;
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static String truncate(String s, int max) {
		return s == null ? "（null）" : (s.length() <= max ? s : s.substring(0, max) + "…");
	}
}
