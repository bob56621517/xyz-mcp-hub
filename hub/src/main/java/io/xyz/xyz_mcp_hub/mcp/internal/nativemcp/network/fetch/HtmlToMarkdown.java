package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * HTML → Markdown 转换器（基于 jsoup），对齐官方 mcp-server-fetch 的 html2text 语义。
 *
 * <p>覆盖标题/段落/有序无序列表/链接/加粗斜体/行内与块级代码/引用/水平线/图片/表格；
 * 丢弃 script/style/nav/header/footer 等导航与脚本噪声，输出正文 markdown。</p>
 */
@Component
public class HtmlToMarkdown {

	private static final List<String> NOISE_SELECTORS =
		List.of("script", "style", "noscript", "iframe", "nav", "footer", "header", "aside", "form", "svg");

	public String convert(String html) {
		Element body = Jsoup.parse(html).body();
		body.select(String.join(",", NOISE_SELECTORS)).remove();
		StringBuilder out = new StringBuilder();
		convertChildren(body, out);
		return out.toString().replaceAll("\n{3,}", "\n\n").strip();
	}

	private void convertChildren(Element parent, StringBuilder out) {
		for (Node node : parent.childNodes()) {
			convertNode(node, out);
		}
	}

	private void convertNode(Node node, StringBuilder out) {
		if (node instanceof TextNode text) {
			out.append(text.text());
			return;
		}
		if (!(node instanceof Element el)) {
			return;
		}
		switch (el.normalName()) {
			case "h1", "h2", "h3", "h4", "h5", "h6" -> heading(el, out);
			case "p" -> block(el, out);
			case "ul" -> unorderedList(el, out);
			case "ol" -> orderedList(el, out);
			case "blockquote" -> blockquote(el, out);
			case "pre" -> codeBlock(el, out);
			case "table" -> table(el, out);
			case "hr" -> {
				blankLine(out);
				out.append("---");
				blankLine(out);
			}
			case "br" -> out.append('\n');
			case "a" -> link(el, out);
			case "strong", "b" -> inlineWrap(el, out, "**");
			case "em", "i" -> inlineWrap(el, out, "*");
			case "code" -> {
				out.append('`').append(el.wholeText()).append('`');
			}
			case "img" -> image(el, out);
			default -> convertChildren(el, out);
		}
	}

	private void heading(Element el, StringBuilder out) {
		blankLine(out);
		int level = Integer.parseInt(el.normalName().substring(1));
		out.append("#".repeat(level)).append(' ');
		convertChildren(el, out);
		blankLine(out);
	}

	private void block(Element el, StringBuilder out) {
		blankLine(out);
		convertChildren(el, out);
		blankLine(out);
	}

	private void unorderedList(Element el, StringBuilder out) {
		for (Element li : el.children()) {
			blankLine(out);
			out.append("- ");
			convertChildren(li, out);
		}
		blankLine(out);
	}

	private void orderedList(Element el, StringBuilder out) {
		int i = 1;
		for (Element li : el.children()) {
			blankLine(out);
			out.append(i++).append(". ");
			convertChildren(li, out);
		}
		blankLine(out);
	}

	private void blockquote(Element el, StringBuilder out) {
		blankLine(out);
		for (String line : el.wholeText().split("\\R")) {
			out.append("> ").append(line.strip()).append('\n');
		}
		blankLine(out);
	}

	private void codeBlock(Element el, StringBuilder out) {
		blankLine(out);
		out.append("```\n").append(el.wholeText().strip()).append("\n```");
		blankLine(out);
	}

	private void link(Element el, StringBuilder out) {
		String href = el.attr("href");
		if (href.isBlank()) {
			convertChildren(el, out);
			return;
		}
		out.append('[');
		convertChildren(el, out);
		out.append("](").append(href).append(')');
	}

	private void inlineWrap(Element el, StringBuilder out, String marker) {
		out.append(marker);
		convertChildren(el, out);
		out.append(marker);
	}

	private void image(Element el, StringBuilder out) {
		String alt = el.attr("alt");
		String src = el.attr("src");
		if (src.isBlank()) {
			return;
		}
		out.append("![").append(alt).append("](").append(src).append(')');
	}

	private void table(Element el, StringBuilder out) {
		blankLine(out);
		for (Element tr : el.select("tr")) {
			StringBuilder cells = new StringBuilder();
			boolean header = false;
			for (Element cell : tr.select("th,td")) {
				cells.append("| ").append(cell.text().strip()).append(' ');
				header |= cell.normalName().equals("th");
			}
			if (cells.isEmpty()) {
				continue;
			}
			cells.append('|');
			out.append(cells);
			blankLine(out);
			if (header) {
				int n = tr.select("th,td").size();
				out.append("| ").append("--- | ".repeat(n));
				blankLine(out);
			}
		}
		blankLine(out);
	}

	private static void blankLine(StringBuilder out) {
		if (out.isEmpty()) {
			return;
		}
		if (out.length() >= 2 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n') {
			return;
		}
		if (out.charAt(out.length() - 1) != '\n') {
			out.append('\n');
		}
		out.append('\n');
	}
}
