package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.playwright;

import java.util.List;
import java.util.Map;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.KeyboardModifier;
import com.microsoft.playwright.options.MouseButton;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Playwright 端点的工具集合：按官方 {@code @playwright/mcp} 工具集实现浏览器自动化。
 *
 * <p>所有浏览器操作工具都要求 {@code sessionId}（由 {@code web_session(action=create)} 返回），
 * 按 sessionId 路由到隔离的浏览器上下文；无 sessionId 或会话已关闭/回收时返回明确错误。
 * 元素定位（target）优先按 CSS 选择器，其次按页面可见文本。截图以
 * {@code data:image/*;base64,...} 形式返回。</p>
 */
@Component
public class PlaywrightTools {

	private final WebSessionRegistry registry;

	public PlaywrightTools(WebSessionRegistry registry) {
		this.registry = registry;
	}

	/**
	 * 取会话句柄：缺失或非法的 sessionId 返回明确错误；会话已关闭/回收时由注册表抛异常。
	 */
	private BrowserSessionHandle handle(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("缺少 sessionId，请先调用 web_session(action=create) 创建会话");
		}
		return registry.handle(sessionId);
	}

	@Tool(name = "web_session", description = "管理浏览器会话租约：action=create 创建新会话并返回 sessionId；action=close 关闭指定 sessionId 并释放浏览器资源；action=list 列出当前活动会话数。每个 sessionId 对应一个隔离的浏览器上下文（独立 cookie/缓存/标签页），所有浏览器操作工具都需携带 sessionId。")
	public String webSession(
			@ToolParam(description = "动作：create / close / list") String action,
			@ToolParam(required = false, description = "close 时必填：要关闭的会话 ID") String sessionId) {
		if (action == null || action.isBlank()) {
			throw new IllegalArgumentException("action 必填，取值 create / close / list");
		}
		return switch (action) {
			case "create" -> {
				try {
					yield "会话已创建，sessionId: " + registry.create();
				}
				catch (IllegalArgumentException e) {
					yield "创建会话失败：" + e.getMessage();
				}
			}
			case "close" -> {
				if (sessionId == null || sessionId.isBlank()) {
					yield "关闭失败：缺少 sessionId。";
				}
				yield registry.close(sessionId)
					? "会话 " + sessionId + " 已关闭并释放浏览器资源。"
					: "会话 " + sessionId + " 不存在或已被关闭/回收。";
			}
			case "list" -> "当前活动会话数：" + registry.activeCount();
			default -> throw new IllegalArgumentException("不支持的 action：" + action + "（取值 create / close / list）");
		};
	}

	@Tool(name = "browser_navigate", description = "导航到指定 URL，等待页面加载完成。sessionId 与 url 必填。")
	public String browserNavigate(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "要导航到的完整 URL，如 https://example.com") String url) {
		try {
			var response = handle(sessionId).navigate(url);
			String status = response == null ? "（本地/无响应导航）" : "（HTTP " + response.status() + "）";
			return "已导航到 " + url + status;
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "导航失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_go_back", description = "返回浏览器历史中的上一页。sessionId 必填。")
	public String browserGoBack(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId) {
		try {
			return handle(sessionId).goBack() ? "已返回上一页。" : "没有可返回的历史记录。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "返回上一页失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_go_forward", description = "前进到浏览器历史中的下一页。sessionId 必填。")
	public String browserGoForward(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId) {
		try {
			return handle(sessionId).goForward() ? "已前进到下一页。" : "没有可前进的历史记录。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "前进失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_snapshot", description = "捕获当前页面结构的可访问性快照（accessibility tree），用于理解页面内容与可交互元素。sessionId 必填。")
	public String browserSnapshot(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId) {
		try {
			return handle(sessionId).snapshot();
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "获取快照失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_take_screenshot", description = "对当前页面截图，返回 base64 data URL。可截取整页或指定元素，支持 png/jpeg/webp。sessionId 必填。")
	public String browserTakeScreenshot(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(required = false, description = "是否截取整个可滚动页面，默认 false") Boolean fullPage,
			@ToolParam(required = false, description = "只截取该元素，CSS 选择器或可见文本") String selector,
			@ToolParam(required = false, description = "图片格式：png / jpeg / webp，默认 png") String type) {
		try {
			String base64 = handle(sessionId).screenshot(fullPage, selector, type);
			String mime = switch (type == null ? "png" : type.toLowerCase()) {
				case "jpeg", "jpg" -> "image/jpeg";
				case "webp" -> "image/webp";
				default -> "image/png";
			};
			return "data:" + mime + ";base64," + base64;
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "截图失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_click", description = "点击页面元素。sessionId 与 target 必填。")
	public String browserClick(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "目标元素，CSS 选择器或可见文本") String target,
			@ToolParam(required = false, description = "鼠标键：left / right / middle，默认 left") String button,
			@ToolParam(required = false, description = "修饰键：Alt / Control / Meta / Shift") List<String> modifiers) {
		try {
			var options = new com.microsoft.playwright.Locator.ClickOptions();
			if (button != null && !button.isBlank()) {
				options.setButton(MouseButton.valueOf(button.toUpperCase()));
			}
			if (modifiers != null && !modifiers.isEmpty()) {
				options.setModifiers(modifiers.stream().map(m -> KeyboardModifier.valueOf(m.toUpperCase())).toList());
			}
			handle(sessionId).locator(target).click(options);
			return "已点击 " + target + "。";
		}
		catch (IllegalArgumentException | PlaywrightException e) {
			return "点击失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_hover", description = "悬停在页面元素上。sessionId 与 target 必填。")
	public String browserHover(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "目标元素，CSS 选择器或可见文本") String target) {
		try {
			handle(sessionId).locator(target).hover();
			return "已悬停 " + target + "。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "悬停失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_type", description = "向可编辑元素输入文本。sessionId 与 target、text 必填；submit 为 true 时输入后按 Enter。")
	public String browserType(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "目标元素，CSS 选择器或可见文本") String target,
			@ToolParam(description = "要输入的文本") String text,
			@ToolParam(required = false, description = "输入后是否按 Enter 提交，默认 false") Boolean submit) {
		try {
			handle(sessionId).locator(target).fill(text);
			if (Boolean.TRUE.equals(submit)) {
				handle(sessionId).locator(target).press("Enter");
			}
			return "已向 " + target + " 输入文本。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "输入失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_press_key", description = "在当前页面按下键盘键，如 Enter、ArrowDown、Tab、a、F5。sessionId 必填。")
	public String browserPressKey(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "按键名，如 Enter、ArrowDown") String key) {
		try {
			handle(sessionId).page().keyboard().press(key);
			return "已按键 " + key + "。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "按键失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_select_option", description = "在下拉框中按值选择选项。sessionId 与 target、values 必填。")
	public String browserSelectOption(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "目标下拉框，CSS 选择器或可见文本") String target,
			@ToolParam(description = "要选中的选项 value 列表，如 [\"option1\", \"option2\"]") List<String> values) {
		try {
			handle(sessionId).locator(target).selectOption(values.toArray(new String[0]));
			return "已选择 " + target + " 的选项：" + values;
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "选择失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_fill_form", description = "批量填充表单字段。sessionId 必填；fields 为对象数组，每项含 target（CSS 选择器或可见文本）、value、type（textbox / checkbox / radio，默认 textbox）。")
	public String browserFillForm(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "待填充字段列表：[{target, value, type}]") List<Map<String, String>> fields) {
		StringBuilder sb = new StringBuilder();
		int done = 0;
		try {
			BrowserSessionHandle handle = handle(sessionId);
			for (Map<String, String> field : fields) {
				String target = field.get("target");
				String value = field.get("value");
				String type = field.getOrDefault("type", "textbox");
				if (target == null || target.isBlank()) {
					throw new IllegalArgumentException("字段缺少 target。");
				}
				switch (type) {
					case "checkbox", "radio" -> {
						if (Boolean.parseBoolean(value)) {
							handle.locator(target).check();
						}
						else {
							handle.locator(target).uncheck();
						}
					}
					default -> {
						if (value == null) {
							throw new IllegalArgumentException("字段 " + target + " 缺少 value。");
						}
						handle.locator(target).fill(value);
					}
				}
				done++;
			}
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			sb.append("填充表单失败：").append(e.getMessage()).append('\n');
		}
		sb.append("已填充 ").append(done).append('/').append(fields.size()).append(" 个字段。");
		return sb.toString();
	}

	@Tool(name = "browser_resize", description = "调整当前页面视口尺寸（CSS 像素）。sessionId 必填。")
	public String browserResize(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "视口宽度") int width,
			@ToolParam(description = "视口高度") int height) {
		try {
			handle(sessionId).resize(width, height);
			return "已将视口调整为 " + width + "x" + height + "。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "调整视口失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_close", description = "关闭当前标签页（不关闭会话）。sessionId 必填。")
	public String browserClose(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId) {
		try {
			return handle(sessionId).closeCurrentPage();
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "关闭标签页失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_tabs", description = "管理浏览器标签页：action 为 list（列出所有标签页）/ new（新建，可传 url）/ close（关闭，可传 index）/ select（切换，传 index）。index 从 0 开始。sessionId 必填。")
	public String browserTabs(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "动作：list / new / close / select") String action,
			@ToolParam(required = false, description = "标签页序号，从 0 开始") Integer index,
			@ToolParam(required = false, description = "新建标签页时导航到的 URL") String url) {
		try {
			return handle(sessionId).tabs(action, index, url);
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "标签页操作失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_handle_dialog", description = "设置本会话浏览器对话框（alert/confirm/prompt）的自动处理：accept 为 true 时自动接受（prompt 对话框可用 promptText 填默认值），false 时自动取消。sessionId 必填。")
	public String browserHandleDialog(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "true 自动接受对话框，false 自动取消") boolean accept,
			@ToolParam(required = false, description = "prompt 对话框的默认输入文本") String promptText) {
		try {
			handle(sessionId).setDialogHandler(accept, promptText);
			return accept ? "对话框将自动接受。" : "对话框将自动取消。";
		}
		catch (IllegalArgumentException e) {
			return "设置对话框处理失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_network_requests", description = "列出页面加载以来的网络请求。sessionId 必填；filter 为 URL 正则；isStatic 为 true 时包含图片/字体/样式等静态资源。")
	public String browserNetworkRequests(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(required = false, description = "按 URL 正则过滤，如 \"/api/.*\"") String filter,
			@ToolParam(required = false, description = "包含静态资源，默认 false") Boolean isStatic) {
		try {
			var list = handle(sessionId).networkRequests(filter, isStatic);
			if (list.isEmpty()) {
				return "没有匹配的网络请求。";
			}
			StringBuilder sb = new StringBuilder("网络请求（共 " + list.size() + " 个）：");
			for (int i = 0; i < list.size(); i++) {
				Map<String, Object> r = list.get(i);
				sb.append('\n').append(i + 1).append(". ")
					.append(r.get("method")).append(' ')
					.append(r.get("url")).append(" (HTTP ")
					.append(r.get("status")).append(") [")
					.append(r.get("resourceType")).append(']');
			}
			return sb.toString();
		}
		catch (RuntimeException e) {
			return "查询网络请求失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_network_request", description = "返回单个网络请求的详情。sessionId 必填；index 为 browser_network_requests 返回的序号（从 1 开始）；part 为 request-headers / request-body / response-headers / response-body，缺省返回全部。")
	public String browserNetworkRequest(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "请求序号，从 1 开始") int index,
			@ToolParam(required = false, description = "返回部分：request-headers / request-body / response-headers / response-body") String part) {
		Map<String, Object> r;
		try {
			r = handle(sessionId).networkRequest(index);
		}
		catch (RuntimeException e) {
			return "查询网络请求失败：" + e.getMessage();
		}
		if (r == null) {
			return "请求序号 " + index + " 不存在。";
		}
		if (part != null && !part.isBlank()) {
			return switch (part) {
				case "request-headers" -> String.valueOf(r.get("requestHeaders"));
				case "request-body" -> r.get("postData") == null ? "" : String.valueOf(r.get("postData"));
				case "response-headers" -> String.valueOf(r.get("responseHeaders"));
				case "response-body" -> String.valueOf(r.get("body"));
				default -> "不支持的 part：" + part;
			};
		}
		StringBuilder sb = new StringBuilder();
		r.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
		return sb.toString().stripTrailing();
	}

	@Tool(name = "browser_console_messages", description = "列出页面加载以来的控制台消息。sessionId 必填；level 为 error / warning / info / debug，返回该级别及更严重级别；all 为 true 时忽略级别返回全部。")
	public String browserConsoleMessages(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(required = false, description = "最低级别：error / warning / info / debug，默认 info") String level,
			@ToolParam(required = false, description = "返回全部消息，忽略 level，默认 false") Boolean all) {
		try {
			var list = all != null && all
				? handle(sessionId).consoleMessages("debug")
				: handle(sessionId).consoleMessages(level);
			if (list.isEmpty()) {
				return "没有控制台消息。";
			}
			StringBuilder sb = new StringBuilder("控制台消息（共 " + list.size() + " 条）：");
			for (Map<String, Object> m : list) {
				sb.append('\n').append('[').append(m.get("type")).append("] ").append(m.get("text"));
			}
			return sb.toString();
		}
		catch (RuntimeException e) {
			return "查询控制台消息失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_evaluate", description = "在当前页面执行 JavaScript 表达式或函数，返回求值结果。sessionId 必填；function 为如 \"document.title\" 或 \"() => document.title\" 的表达式。")
	public String browserEvaluate(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "要执行的 JS 表达式或函数") String function) {
		try {
			Object result = handle(sessionId).evaluate(function);
			return result == null ? "null" : String.valueOf(result);
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "执行失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_wait_for", description = "等待页面出现/消失指定文本，或等待固定时间。sessionId 必填；text 出现、textGone 消失、time（秒）等待时长，三选一。")
	public String browserWaitFor(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(required = false, description = "等待该文本出现") String text,
			@ToolParam(required = false, description = "等待该文本消失") String textGone,
			@ToolParam(required = false, description = "等待时长（秒）") Double time) {
		try {
			BrowserSessionHandle handle = handle(sessionId);
			if (text != null && !text.isBlank()) {
				handle.waitForText(text);
				return "等待到文本出现：" + text;
			}
			if (textGone != null && !textGone.isBlank()) {
				handle.waitForTextGone(textGone);
				return "等待到文本消失：" + textGone;
			}
			if (time != null) {
				handle.waitForTimeout(time);
				return "已等待 " + time + " 秒。";
			}
			return "请提供 text、textGone 或 time 之一。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "等待超时：" + e.getMessage();
		}
	}

	@Tool(name = "browser_find", description = "在页面可访问性快照中查找包含指定文本的行。sessionId 必填；text 为大小写不敏感子串；regex 为正则表达式，二选一。")
	public String browserFind(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(required = false, description = "要查找的子串") String text,
			@ToolParam(required = false, description = "要匹配的正则表达式") String regex) {
		try {
			String snapshot = handle(sessionId).snapshot();
			StringBuilder sb = new StringBuilder();
			java.util.regex.Pattern pattern = (regex == null || regex.isBlank())
				? null
				: java.util.regex.Pattern.compile(regex);
			String lower = text == null ? "" : text.toLowerCase();
			String[] lines = snapshot.split("\n");
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				boolean matched = pattern != null
					? pattern.matcher(line).find()
					: (!lower.isEmpty() && line.toLowerCase().contains(lower));
				if (matched) {
					if (i > 0) {
						sb.append(lines[i - 1]).append('\n');
					}
					sb.append(line).append('\n');
					if (i + 1 < lines.length) {
						sb.append(lines[i + 1]).append('\n');
					}
				}
			}
			return sb.length() == 0 ? "未找到匹配。\n" + snapshot : sb.toString().stripTrailing();
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "查找失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_drag", description = "把元素拖放到目标元素上。sessionId 必填；from / to 均为 CSS 选择器或可见文本。")
	public String browserDrag(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "被拖拽元素") String from,
			@ToolParam(description = "拖放目标元素") String to) {
		try {
			handle(sessionId).drag(from, to);
			return "已从 " + from + " 拖放到 " + to + "。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "拖放失败：" + e.getMessage();
		}
	}

	@Tool(name = "browser_file_upload", description = "向文件上传元素上传本地文件。sessionId 与 target、paths 必填。")
	public String browserFileUpload(
			@ToolParam(required = false, description = "web_session(action=create) 返回的会话 ID") String sessionId,
			@ToolParam(description = "目标 input[type=file] 元素") String target,
			@ToolParam(description = "要上传的本地文件绝对路径列表") List<String> paths) {
		try {
			handle(sessionId).setInputFiles(target, paths);
			return "已上传 " + paths.size() + " 个文件到 " + target + "。";
		}
		catch (PlaywrightException | IllegalArgumentException e) {
			return "上传失败：" + e.getMessage();
		}
	}

}
