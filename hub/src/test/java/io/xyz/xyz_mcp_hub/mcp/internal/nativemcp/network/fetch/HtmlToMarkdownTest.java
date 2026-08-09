package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link HtmlToMarkdown} 单测：标题/段落/列表/链接/代码/引用/表格转换与噪声移除。
 * 纯逻辑测试，无网络依赖。
 */
class HtmlToMarkdownTest {

	private final HtmlToMarkdown converter = new HtmlToMarkdown();

	@Test
	void convertsHeadingsParagraphsAndLinks() {
		String html = """
			<h1>Hello World</h1>
			<p>This is a <a href="https://example.com">link</a> paragraph.</p>
			""";
		String md = converter.convert(html);
		assertThat(md).contains("# Hello World");
		assertThat(md).contains("This is a [link](https://example.com) paragraph.");
	}

	@Test
	void convertsListsWithOrdering() {
		String html = """
			<ul><li>alpha</li><li>beta</li></ul>
			<ol><li>first</li><li>second</li></ol>
			""";
		String md = converter.convert(html);
		assertThat(md).contains("- alpha");
		assertThat(md).contains("- beta");
		assertThat(md).contains("1. first");
		assertThat(md).contains("2. second");
	}

	@Test
	void convertsInlineAndBlockCode() {
		String html = """
			<p>Run <code>mvn test</code> now.</p>
			<pre><code>public void main() {}</code></pre>
			""";
		String md = converter.convert(html);
		assertThat(md).contains("`mvn test`");
		assertThat(md).contains("```");
		assertThat(md).contains("public void main() {}");
	}

	@Test
	void convertsBlockquote() {
		String md = converter.convert("<blockquote>Keep it simple</blockquote>");
		assertThat(md).contains("> Keep it simple");
	}

	@Test
	void convertsTable() {
		String html = """
			<table>
			<tr><th>Name</th><th>Age</th></tr>
			<tr><td>Alice</td><td>30</td></tr>
			</table>
			""";
		String md = converter.convert(html);
		assertThat(md).contains("| Name | Age |");
		assertThat(md).contains("| --- | --- |");
		assertThat(md).contains("| Alice | 30 |");
	}

	@Test
	void stripsNoiseElements() {
		String html = """
			<nav><a href="/">Menu</a></nav>
			<script>alert('x')</script>
			<p>Visible text</p>
			<style>.x{}</style>
			""";
		String md = converter.convert(html);
		assertThat(md).contains("Visible text");
		assertThat(md).doesNotContain("Menu", "alert", "x{}");
	}

	@Test
	void convertsInlineFormatting() {
		String md = converter.convert("<p>bold <strong>B</strong> and <em>italic</em></p>");
		assertThat(md).contains("**B**");
		assertThat(md).contains("*italic*");
	}
}
