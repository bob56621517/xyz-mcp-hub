package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host.playwright;

import java.util.List;

import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.playwright.BrowserSessionHandle;
import io.xyz.xyz_mcp_hub.playwright.WebSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlaywrightTools 工具类即源测试（#53/#54）：验证 {@link PlaywrightTools} 作为 {@code McpEndpointProvider}
 * 的源元数据（name/scope/getTools/isEnabled）与会话租约语义（web_session create/close/list）、
 * 错误路径（缺 sessionId、会话已关闭/回收）及 {@code @Tool} 方法对纯能力 {@link WebSessionRegistry}
 * /{@link BrowserSessionHandle} 的委托转发。浏览器真实操作由手工冒烟 {@code PlaywrightSmoke} 覆盖
 * （需 chromium）；此处 mock 注册表与句柄，**不启 chromium、不启 Spring、不触网**。
 */
class PlaywrightToolsTest {

	private final WebSessionRegistry registry = mock(WebSessionRegistry.class);
	private final BrowserSessionHandle handle = mock(BrowserSessionHandle.class);

	private PlaywrightTools tools() {
		return new PlaywrightTools(registry);
	}

	// ---- 源元数据（工具类即源） ----

	@Test
	void exposesSourceMetadata() {
		PlaywrightTools tools = tools();
		assertThat(tools.getName()).isEqualTo("playwright");
		assertThat(tools.getScope()).isEqualTo(Scope.HOST);
		// web_session + 23 个 browser_* 工具
		assertThat(tools.getTools()).hasSize(24);
	}

	@Test
	void enabledByDefault() {
		assertThat(tools().isEnabled()).isTrue();
	}

	@Test
	void listToolsExposesSessionAndBrowserTools() {
		assertThat(tools().getTools()).extracting(ToolCallback::getToolDefinition)
			.extracting(td -> td.name())
			.contains("web_session", "browser_navigate", "browser_snapshot", "browser_evaluate",
					"browser_click", "browser_take_screenshot", "browser_wait_for");
	}

	// ---- web_session：create / close / list 委托 ----

	@Test
	void webSessionCreateDelegatesToRegistry() {
		when(registry.create()).thenReturn("ws-1");
		assertThat(tools().webSession("create", null)).isEqualTo("会话已创建，sessionId: ws-1");
		verify(registry).create();
	}

	@Test
	void webSessionCreateWhenFullReturnsError() {
		when(registry.create()).thenThrow(new IllegalArgumentException("浏览器会话数已达上限 2，请先关闭"));
		assertThat(tools().webSession("create", null)).isEqualTo("创建会话失败：浏览器会话数已达上限 2，请先关闭");
	}

	@Test
	void webSessionCloseDelegatesToRegistry() {
		when(registry.close("ws-1")).thenReturn(true);
		assertThat(tools().webSession("close", "ws-1")).isEqualTo("会话 ws-1 已关闭并释放浏览器资源。");
		verify(registry).close("ws-1");
	}

	@Test
	void webSessionCloseNotFoundReturnsHint() {
		when(registry.close("ws-dead")).thenReturn(false);
		assertThat(tools().webSession("close", "ws-dead"))
			.isEqualTo("会话 ws-dead 不存在或已被关闭/回收。");
	}

	@Test
	void webSessionCloseBlankSessionIdReturnsHint() {
		assertThat(tools().webSession("close", null)).isEqualTo("关闭失败：缺少 sessionId。");
		verify(registry, never()).close(anyString());
	}

	@Test
	void webSessionListDelegatesToRegistry() {
		when(registry.activeCount()).thenReturn(2);
		assertThat(tools().webSession("list", null)).isEqualTo("当前活动会话数：2");
	}

	@Test
	void webSessionBlankActionThrows() {
		assertThatThrownBy(() -> tools().webSession(null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("action 必填");
		assertThatThrownBy(() -> tools().webSession("", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("action 必填");
	}

	// ---- 错误路径：缺 sessionId / 会话已关闭 ----

	@Test
	void missingSessionIdReturnsClearError() {
		assertThat(tools().browserNavigate(null, "https://example.com"))
			.contains("缺少 sessionId");
		verify(registry, never()).handle(anyString());
	}

	@Test
	void closedOrReclaimedSessionReturnsClearError() {
		when(registry.handle("ws-dead"))
			.thenThrow(new IllegalArgumentException("会话 ws-dead 不存在或已被关闭/回收，请先创建"));
		assertThat(tools().browserSnapshot("ws-dead"))
			.contains("获取快照失败").contains("不存在或已被关闭/回收");
	}

	// ---- @Tool 委托：mock 句柄验证路由 ----

	@Test
	void browserNavigateDelegatesToHandle() {
		Response response = mock(Response.class);
		when(response.status()).thenReturn(200);
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.navigate("https://example.com")).thenReturn(response);

		assertThat(tools().browserNavigate("ws-1", "https://example.com"))
			.isEqualTo("已导航到 https://example.com（HTTP 200）");
		verify(handle).navigate("https://example.com");
	}

	@Test
	void browserNavigateNullResponseIsLocalNavigation() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.navigate("about:blank")).thenReturn(null);

		assertThat(tools().browserNavigate("ws-1", "about:blank"))
			.isEqualTo("已导航到 about:blank（本地/无响应导航）");
	}

	@Test
	void browserSnapshotReturnsHandleSnapshot() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.snapshot()).thenReturn("title: Test Page\nurl: http://localhost/\n\nroot");

		assertThat(tools().browserSnapshot("ws-1"))
			.contains("title: Test Page").contains("root");
		verify(handle).snapshot();
	}

	@Test
	void browserEvaluateDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.evaluate("document.title")).thenReturn("标题");

		assertThat(tools().browserEvaluate("ws-1", "document.title")).isEqualTo("标题");
		verify(handle).evaluate("document.title");
	}

	@Test
	void browserClickDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.locator("#counter")).thenReturn(mock(com.microsoft.playwright.Locator.class));

		assertThat(tools().browserClick("ws-1", "#counter", null, null)).isEqualTo("已点击 #counter。");
		verify(handle).locator("#counter");
	}

	@Test
	void browserTakeScreenshotReturnsDataUrl() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.screenshot(false, null, "png")).thenReturn("aGVsbG8=");

		assertThat(tools().browserTakeScreenshot("ws-1", false, null, "png"))
			.isEqualTo("data:image/png;base64,aGVsbG8=");
		verify(handle).screenshot(false, null, "png");
	}

	@Test
	void browserWaitForDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);

		assertThat(tools().browserWaitFor("ws-1", "动态文本", null, null))
			.isEqualTo("等待到文本出现：动态文本");
		verify(handle).waitForText("动态文本");
	}

	@Test
	void browserWaitForTextGoneDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);

		assertThat(tools().browserWaitFor("ws-1", null, "动态文本", null))
			.isEqualTo("等待到文本消失：动态文本");
		verify(handle).waitForTextGone("动态文本");
	}

	@Test
	void browserWaitForTimeoutDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);

		assertThat(tools().browserWaitFor("ws-1", null, null, 0.5))
			.isEqualTo("已等待 0.5 秒。");
		verify(handle).waitForTimeout(0.5);
	}

	@Test
	void browserNetworkRequestsDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.networkRequests("/api/ping", false)).thenReturn(List.of(
				java.util.Map.of("method", "GET", "url", "http://localhost/api/ping", "status", 200, "resourceType", "xhr")));

		assertThat(tools().browserNetworkRequests("ws-1", "/api/ping", false))
			.contains("网络请求（共 1 个）").contains("GET").contains("/api/ping").contains("HTTP 200");
		verify(handle).networkRequests("/api/ping", false);
	}

	// ---- 其余 @Tool 委托：补齐 24 个工具的每个路由 ----

	@Test
	void browserGoBackDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.goBack()).thenReturn(true);
		assertThat(tools().browserGoBack("ws-1")).isEqualTo("已返回上一页。");
		verify(handle).goBack();
	}

	@Test
	void browserGoBackNoHistoryReturnsHint() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.goBack()).thenReturn(false);
		assertThat(tools().browserGoBack("ws-1")).isEqualTo("没有可返回的历史记录。");
	}

	@Test
	void browserGoForwardDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.goForward()).thenReturn(true);
		assertThat(tools().browserGoForward("ws-1")).isEqualTo("已前进到下一页。");
		verify(handle).goForward();
	}

	@Test
	void browserHoverDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.locator("#btn")).thenReturn(mock(Locator.class));
		assertThat(tools().browserHover("ws-1", "#btn")).isEqualTo("已悬停 #btn。");
		verify(handle).locator("#btn");
	}

	@Test
	void browserTypeDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.locator("#name")).thenReturn(mock(Locator.class));
		assertThat(tools().browserType("ws-1", "#name", "hello", false)).isEqualTo("已向 #name 输入文本。");
		verify(handle.locator("#name")).fill("hello");
	}

	@Test
	void browserTypeSubmitPressesEnter() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.locator("#name")).thenReturn(mock(Locator.class));
		tools().browserType("ws-1", "#name", "hello", true);
		verify(handle.locator("#name")).press("Enter");
	}

	@Test
	void browserPressKeyDelegatesToKeyboard() {
		Page page = mock(Page.class);
		Keyboard keyboard = mock(Keyboard.class);
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.page()).thenReturn(page);
		when(page.keyboard()).thenReturn(keyboard);
		assertThat(tools().browserPressKey("ws-1", "Enter")).isEqualTo("已按键 Enter。");
		verify(keyboard).press("Enter");
	}

	@Test
	void browserSelectOptionDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.locator("#color")).thenReturn(mock(Locator.class));
		assertThat(tools().browserSelectOption("ws-1", "#color", List.of("green")))
			.isEqualTo("已选择 #color 的选项：[green]");
		verify(handle.locator("#color")).selectOption(new String[]{"green"});
	}

	@Test
	void browserFillFormDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.locator(anyString())).thenReturn(mock(Locator.class));
		String result = tools().browserFillForm("ws-1", List.of(
				java.util.Map.of("target", "#name", "value", "hello"),
				java.util.Map.of("target", "#agree", "value", "true", "type", "checkbox")));
		assertThat(result).contains("已填充 2/2 个字段。");
		verify(handle.locator("#name")).fill("hello");
		verify(handle.locator("#agree")).check();
	}

	@Test
	void browserResizeDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		assertThat(tools().browserResize("ws-1", 800, 600)).isEqualTo("已将视口调整为 800x600。");
		verify(handle).resize(800, 600);
	}

	@Test
	void browserCloseDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.closeCurrentPage()).thenReturn("已关闭标签页。");
		assertThat(tools().browserClose("ws-1")).isEqualTo("已关闭标签页。");
		verify(handle).closeCurrentPage();
	}

	@Test
	void browserTabsDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.tabs("list", null, null)).thenReturn("标签页列表（共 1 个）：\n[0] title (url)");
		assertThat(tools().browserTabs("ws-1", "list", null, null))
			.isEqualTo("标签页列表（共 1 个）：\n[0] title (url)");
		verify(handle).tabs("list", null, null);
	}

	@Test
	void browserHandleDialogDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		assertThat(tools().browserHandleDialog("ws-1", true, null)).isEqualTo("对话框将自动接受。");
		verify(handle).setDialogHandler(true, null);
	}

	@Test
	void browserNetworkRequestDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.networkRequest(1)).thenReturn(java.util.Map.of(
				"method", "GET", "url", "http://localhost/api/ping",
				"requestHeaders", java.util.Map.of("content-type", "application/json"),
				"postData", "{}", "responseHeaders", java.util.Map.of("content-type", "application/json"),
				"body", "pong"));
		assertThat(tools().browserNetworkRequest("ws-1", 1, "request-body")).isEqualTo("{}");
		verify(handle).networkRequest(1);
	}

	@Test
	void browserNetworkRequestMissingIndexReturnsHint() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.networkRequest(99)).thenReturn(null);
		assertThat(tools().browserNetworkRequest("ws-1", 99, null)).isEqualTo("请求序号 99 不存在。");
	}

	@Test
	void browserConsoleMessagesDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.consoleMessages("info")).thenReturn(List.of(
				java.util.Map.of("type", "log", "text", "hello-console")));
		assertThat(tools().browserConsoleMessages("ws-1", "info", false))
			.contains("控制台消息（共 1 条）").contains("hello-console");
		verify(handle).consoleMessages("info");
	}

	@Test
	void browserFindMatchesSnapshotLines() {
		when(registry.handle("ws-1")).thenReturn(handle);
		when(handle.snapshot()).thenReturn("title: Playwright Test Page\nurl: http://localhost/\n\nroot");
		assertThat(tools().browserFind("ws-1", "Playwright Test Page", null))
			.contains("title: Playwright Test Page").contains("url: http://localhost/");
	}

	@Test
	void browserDragDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		assertThat(tools().browserDrag("ws-1", "#from", "#to")).isEqualTo("已从 #from 拖放到 #to。");
		verify(handle).drag("#from", "#to");
	}

	@Test
	void browserFileUploadDelegatesToHandle() {
		when(registry.handle("ws-1")).thenReturn(handle);
		assertThat(tools().browserFileUpload("ws-1", "#file", List.of("C:/tmp/a.txt")))
			.isEqualTo("已上传 1 个文件到 #file。");
		verify(handle).setInputFiles("#file", List.of("C:/tmp/a.txt"));
	}
}
