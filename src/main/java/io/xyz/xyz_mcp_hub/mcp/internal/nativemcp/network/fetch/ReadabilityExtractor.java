package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;

/**
 * 页面上下文内容提取器：把 Readability.js 原版与 turndown 原版注入 Playwright 页面，
 * 在 {@code page.evaluate} 中执行——不移植、不失真。
 *
 * <p>资源来源：{@code fetch/readability/Readability.js}（Mozilla Readability，Apache-2.0）
 * 与 {@code fetch/turndown/turndown.js}（domchristie/turndown，MIT）均为原版构建产物，注入
 * 页面后以全局名 {@code Readability} / {@code TurndownService} 可用。</p>
 *
 * <p>双路径：{@link #extractMarkdown(Page)} 用 Readability 清洗正文后转 markdown；
 * {@link #extractRawHtml(Page)} 返回渲染后完整 HTML 结构。</p>
 */
@Component
public class ReadabilityExtractor {

	private final String readabilityJs;
	private final String turndownJs;

	public ReadabilityExtractor() {
		this.readabilityJs = loadResource("/fetch/readability/Readability.js");
		this.turndownJs = loadResource("/fetch/turndown/turndown.js");
	}

	/** 正文路径：Readability 清洗 + turndown 转 markdown；无法提取时回退页面可见文本。 */
	public String extractMarkdown(Page page) {
		injectLibraries(page);
		Object result = page.evaluate("""
				() => {
				  const article = new Readability(document.cloneNode(true)).parse();
				  if (!article || !article.content) {
				    return { title: '', markdown: '' };
				  }
				  const service = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced' });
				  service.remove(['script', 'style', 'noscript', 'iframe', 'nav', 'footer', 'aside', 'form', 'svg']);
				  return { title: article.title || '', markdown: service.turndown(article.content) || '' };
				}
				""");
		Map<String, Object> map = asMap(result);
		String title = String.valueOf(map.getOrDefault("title", ""));
		String markdown = String.valueOf(map.getOrDefault("markdown", ""));
		if (markdown.isBlank()) {
			return fallbackText(page);
		}
		return title.isBlank() ? markdown : "# " + title + "\n\n" + markdown;
	}

	/** raw 路径：渲染后完整 HTML 结构（含 JS 生成内容）。不注入提取库，避免污染原始结构。 */
	public String extractRawHtml(Page page) {
		Object result = page.evaluate("() => document.documentElement ? document.documentElement.outerHTML : ''");
		String html = result == null ? "" : String.valueOf(result);
		return html.isBlank() ? "页面无 HTML 结构。" : html;
	}

	private void injectLibraries(Page page) {
		page.addScriptTag(new Page.AddScriptTagOptions().setContent(readabilityJs));
		page.addScriptTag(new Page.AddScriptTagOptions().setContent(turndownJs));
	}

	private String fallbackText(Page page) {
		Object text = page.evaluate("() => document.body ? document.body.innerText : ''");
		String value = text == null ? "" : String.valueOf(text);
		return value.isBlank() ? "未能提取正文内容。" : value.strip();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object result) {
		return result instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static String loadResource(String path) {
		try (InputStream in = ReadabilityExtractor.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("缺少 fetch 浏览器路径资源：" + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取 fetch 浏览器路径资源失败：" + path, e);
		}
	}
}
