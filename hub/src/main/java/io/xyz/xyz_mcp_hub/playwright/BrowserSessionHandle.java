package io.xyz.xyz_mcp_hub.playwright;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * 单个浏览器会话的句柄：持有**独立的** {@link Playwright} 连接与 {@link BrowserContext}，
 * 通过 {@code connectOverCDP} 连接到 {@link SharedChromium} 共享的浏览器进程。一个 sessionId
 * 对应一个实例。
 *
 * <p>每会话独立连接 → 独立 driver 连接与对象引用表 → 线程安全，不同会话可真正并发
 * （规避单 Playwright 实例跨线程报 {@code Object doesn't exist}）。同会话内操作仍加
 * {@code synchronized} 串行。{@link #lastAccessNanos} 用于注册表 TTL 自动回收。</p>
 */
public class BrowserSessionHandle implements AutoCloseable {

	/** 单会话内保留的最大请求/控制台消息条数，超出丢弃最旧。 */
	private static final int MAX_RECORDS = 500;

	/** 记录的响应体最大长度，超出截断，避免内存膨胀。 */
	private static final int MAX_BODY_LENGTH = 65536;

	private static final List<String> STATIC_RESOURCE_TYPES =
		List.of("image", "font", "stylesheet", "script", "media");

	private final double navigationTimeoutSeconds;

	private final Playwright playwright;
	private final Browser browser;
	private final BrowserContext context;

	private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
	private final List<Map<String, Object>> consoleMessages = new CopyOnWriteArrayList<>();

	private volatile Page currentPage;
	private volatile boolean dialogAutoAccept = true;
	private volatile String dialogPromptText;
	private volatile long lastAccessNanos;
	private volatile boolean closed;

	public BrowserSessionHandle(String cdpEndpoint, double navigationTimeoutSeconds) {
		this.navigationTimeoutSeconds = navigationTimeoutSeconds;
		this.playwright = Playwright.create();
		this.browser = playwright.chromium().connectOverCDP(cdpEndpoint);
		this.context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 720));
		context.onResponse(this::recordResponse);
		context.onConsoleMessage(this::recordConsoleMessage);
		context.onDialog(dialog -> {
			if (dialogAutoAccept) {
				if (dialogPromptText != null) {
					dialog.accept(dialogPromptText);
				}
				else {
					dialog.accept();
				}
			}
			else {
				dialog.dismiss();
			}
		});
		touch();
	}

	/** 记录一次访问，用于 TTL 判断（每次通过注册表取句柄时更新）。 */
	public void touch() {
		lastAccessNanos = System.nanoTime();
	}

	long lastAccessNanos() {
		return lastAccessNanos;
	}

	/** 当前活动标签页；无则懒创建新页。 */
	public synchronized Page page() {
		if (currentPage == null || currentPage.isClosed()) {
			List<Page> pages = context.pages();
			currentPage = pages.isEmpty() ? context.newPage() : pages.get(pages.size() - 1);
		}
		return currentPage;
	}

	public synchronized Response navigate(String url) {
		return page().navigate(url,
				new Page.NavigateOptions().setTimeout(navigationTimeoutSeconds * 1000));
	}

	public synchronized boolean goBack() {
		Response r = page().goBack();
		return r != null;
	}

	public synchronized boolean goForward() {
		Response r = page().goForward();
		return r != null;
	}

	public synchronized String snapshot() {
		Page page = page();
		return "title: " + page.title() + "\nurl: " + page.url() + "\n\n" + page.ariaSnapshot();
	}

	public synchronized String screenshot(Boolean fullPage, String selector, String type) {
		byte[] bytes;
		if (selector != null && !selector.isBlank()) {
			bytes = locator(selector).screenshot(new Locator.ScreenshotOptions().setType(parseType(type)));
		}
		else {
			bytes = page().screenshot(new Page.ScreenshotOptions()
				.setFullPage(Boolean.TRUE.equals(fullPage))
				.setType(parseType(type)));
		}
		return java.util.Base64.getEncoder().encodeToString(bytes);
	}

	private ScreenshotType parseType(String type) {
		if (type == null || type.isBlank()) {
			return ScreenshotType.PNG;
		}
		return switch (type.toLowerCase()) {
			case "jpeg", "jpg" -> ScreenshotType.JPEG;
			case "webp" -> ScreenshotType.WEBP;
			default -> ScreenshotType.PNG;
		};
	}

	public synchronized void resize(int width, int height) {
		page().setViewportSize(width, height);
	}

	public synchronized String closeCurrentPage() {
		Page page = currentPage;
		if (page == null || page.isClosed()) {
			return "当前没有打开的标签页。";
		}
		page.close();
		currentPage = null;
		return "已关闭标签页。";
	}

	/** 标签页管理：list / new / close / select。index 从 0 开始对应标签页列表。 */
	public synchronized String tabs(String action, Integer index, String url) {
		switch (action) {
			case "list" -> {
				List<Page> pages = context.pages();
				StringBuilder sb = new StringBuilder("标签页列表（共 " + pages.size() + " 个）：");
				for (int i = 0; i < pages.size(); i++) {
					Page p = pages.get(i);
					sb.append("\n[").append(i).append("] ")
						.append(p.title().isBlank() ? p.url() : p.title())
						.append(" (").append(p.url()).append(")");
				}
				return sb.toString();
			}
			case "new" -> {
				currentPage = context.newPage();
				if (url != null && !url.isBlank()) {
					currentPage.navigate(url,
							new Page.NavigateOptions().setTimeout(navigationTimeoutSeconds * 1000));
				}
				return "已新建标签页。";
			}
			case "close" -> {
				if (index == null) {
					return closeCurrentPage();
				}
				if (index < 0 || index >= context.pages().size()) {
					return "标签页序号 " + index + " 不存在。";
				}
				context.pages().get(index).close();
				currentPage = null;
				return "已关闭标签页 [" + index + "]。";
			}
			case "select" -> {
				if (index == null || index < 0 || index >= context.pages().size()) {
					return "标签页序号 " + index + " 不存在。";
				}
				currentPage = context.pages().get(index);
				return "已切换到标签页 [" + index + "]。";
			}
			default -> throw new IllegalArgumentException("不支持的 tabs 动作：" + action
				+ "（取值 list / new / close / select）");
		}
	}

	public synchronized void setDialogHandler(boolean accept, String promptText) {
		dialogAutoAccept = accept;
		dialogPromptText = promptText;
	}

	/**
	 * 解析元素定位：优先按 CSS 选择器（至少匹配一个元素），否则按页面可见文本精确匹配。
	 */
	public synchronized Locator locator(String target) {
		if (target == null || target.isBlank()) {
			throw new IllegalArgumentException("target 不能为空，请提供 CSS 选择器或页面可见文本。");
		}
		Page page = page();
		try {
			Locator css = page.locator(target);
			if (css.count() > 0) {
				return css.first();
			}
		}
		catch (RuntimeException ignored) {
			// 非法 CSS 选择器时回退到文本匹配
		}
		Locator byText = page.getByText(target);
		if (byText.count() == 0) {
			throw new IllegalArgumentException("页面上未找到与 「" + target + "」 匹配的元素。");
		}
		return byText.first();
	}

	public synchronized Object evaluate(String function) {
		return page().evaluate(function);
	}

	public synchronized void waitForText(String text) {
		// getByText 可能匹配多个元素触发 strict mode，取第一个；与 locator() 的文本回退一致
		page().getByText(text).first().waitFor();
	}

	public synchronized void waitForTextGone(String text) {
		page().getByText(text).first().waitFor(
				new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
	}

	public synchronized void waitForTimeout(double seconds) {
		// Playwright Java 的 waitForTimeout 参数为毫秒，此处入参为秒
		page().waitForTimeout(seconds * 1000);
	}

	public synchronized void setInputFiles(String target, List<String> paths) {
		java.nio.file.Path[] files = paths.stream()
			.map(java.nio.file.Path::of)
			.toArray(java.nio.file.Path[]::new);
		locator(target).setInputFiles(files);
	}

	public synchronized void drag(String from, String to) {
		locator(from).dragTo(locator(to));
	}

	/**
	 * 已记录的网络请求（可选按 URL 正则过滤）。static 为 true 时包含图片/字体/样式等静态资源，
	 * 缺省排除静态资源，与官方工具语义一致。
	 */
	public List<Map<String, Object>> networkRequests(String filter, Boolean isStatic) {
		java.util.regex.Pattern pattern = (filter == null || filter.isBlank())
			? null
			: java.util.regex.Pattern.compile(filter);
		return requests.stream()
			.filter(r -> pattern == null || pattern.matcher(String.valueOf(r.get("url"))).find())
			.filter(r -> Boolean.TRUE.equals(isStatic)
				|| !STATIC_RESOURCE_TYPES.contains(String.valueOf(r.get("resourceType"))))
			.toList();
	}

	/** 按 1 基序号取单条请求记录；越界返回 null。 */
	public synchronized Map<String, Object> networkRequest(int index) {
		if (index < 1 || index > requests.size()) {
			return null;
		}
		return requests.get(index - 1);
	}

	public List<Map<String, Object>> consoleMessages(String level) {
		int minSeverity = switch (level == null ? "info" : level.toLowerCase()) {
			case "error" -> 0;
			case "warning", "warn" -> 1;
			case "info" -> 2;
			case "debug" -> 3;
			default -> 2;
		};
		return consoleMessages.stream()
			.filter(m -> severity(String.valueOf(m.get("type"))) >= minSeverity)
			.toList();
	}

	private int severity(String type) {
		return switch (type) {
			case "error" -> 0;
			case "warning" -> 1;
			case "log", "info", "dir", "count", "assert" -> 2;
			default -> 3;
		};
	}

	private void recordResponse(Response response) {
		if (requests.size() >= MAX_RECORDS) {
			requests.remove(0);
		}
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("method", response.request().method());
		record.put("url", response.url());
		record.put("status", response.status());
		record.put("resourceType", response.request().resourceType());
		record.put("requestHeaders", response.request().headers());
		record.put("postData", response.request().postData());
		record.put("responseHeaders", response.headers());
		record.put("body", readBody(response));
		requests.add(record);
	}

	private void recordConsoleMessage(ConsoleMessage message) {
		if (consoleMessages.size() >= MAX_RECORDS) {
			consoleMessages.remove(0);
		}
		consoleMessages.add(Map.of("type", message.type(), "text", message.text()));
	}

	private String readBody(Response response) {
		try {
			byte[] body = response.body();
			if (body == null || body.length == 0) {
				return "";
			}
			if (body.length > MAX_BODY_LENGTH) {
				body = java.util.Arrays.copyOf(body, MAX_BODY_LENGTH);
			}
			return new String(body, java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (RuntimeException e) {
			return "";
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			context.close();
		}
		catch (RuntimeException ignored) {
			// 连接可能已断开，忽略
		}
		try {
			browser.close();
		}
		catch (RuntimeException ignored) {
			// connectOverCDP 的 close 只断开本客户端连接，失败不影响进程清理
		}
		try {
			playwright.close();
		}
		catch (RuntimeException ignored) {
			// 忽略，释放尽力而为
		}
	}

}
