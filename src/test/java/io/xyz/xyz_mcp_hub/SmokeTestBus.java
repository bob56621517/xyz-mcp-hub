package io.xyz.xyz_mcp_hub;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.utils.UtilsTools;

/**
 * 统一冒烟总线（手工运行，非自动测试）：集中调度各服务的独立 main 冒烟，逐项输出
 * {@code [PASS]/[FAIL]/[SKIP]} 与步骤化 stdout，最后生成汇总报告，作为验收证据可贴 issue。
 *
 * <p>每个任务 try-catch + 超时保护包裹；依赖缺失（API key / token / chromium）判定为
 * {@code SKIP}（环境未就绪，不视为服务失败）。各服务独立 main 遵循测试指南保留
 * （含 {@code @requires-*} 声明），本总线调度它们并汇总。</p>
 *
 * <p>运行：{@code ./mvnw exec:java -Dexec.mainClass=io.xyz.xyz_mcp_hub.SmokeTestBus -Dexec.classpathScope=test -Dvaadin.skip=true}</p>
 *
 * @requires-web utils/fetch 外各任务需真实外部网络（api.bochaai.com / api.githubcopilot.com / mcp.context7.com / mcp.grep.app / wd-mcp.wmcloud.org）
 * @requires-web fetch 任务需真实外部网络（example.com / www.w3.org）
 * @requires-token BOCHA_API_KEY bocha 冒烟；未设置则跳过
 * @requires-token GITHUB_TOKEN github 冒烟；未设置则跳过
 * @requires-service chromium playwright 冒烟；未安装则跳过
 */
public class SmokeTestBus {

	private static final long TASK_TIMEOUT_SECONDS = 90;

	public static void main(String[] args) {
		List<SmokeTask> tasks = List.of(
				new SmokeTask("utils", "本地时间工具（无外部依赖）", SmokeTestBus::smokeUtils),
				new SmokeTask("bocha", "博查搜索（需 BOCHA_API_KEY）",
						() -> callMain(() -> {
							BochaRealApiSmoke.main(new String[0]);
							return null;
						})),
				new SmokeTask("github", "GitHub MCP（需 GITHUB_TOKEN）",
						() -> callMain(() -> {
							GithubRealApiSmoke.main(new String[0]);
							return null;
						})),
				new SmokeTask("public-proxy", "context7/grep-app/wikidata 公共代理",
						() -> callMain(() -> {
							PublicProxyRealApiSmoke.main(new String[0]);
							return null;
						})),
				new SmokeTask("playwright", "浏览器自动化（需 chromium）",
						() -> callMain(() -> {
							McpPlaywrightEndpointTest.main(new String[0]);
							return null;
						})),
				new SmokeTask("fetch", "快路径抓取（HTML/PDF/SSRF 拦截，@requires-web）+ 浏览器路径（engine=browser，JS 渲染/双路径/子资源 SSRF/截图，@requires-service chromium）",
						() -> callMain(() -> {
							FetchRealApiSmoke.main(new String[0]);
							return null;
						})));

		System.out.println("=== Smoke Test Bus ===");
		int pass = 0;
		int fail = 0;
		int skip = 0;
		List<String> failures = new ArrayList<>();
		for (SmokeTask task : tasks) {
			SmokeResult result = run(task);
			System.out.println("[" + result.status() + "] " + task.name() + "（" + task.description() + "） — " + result.detail());
			switch (result.status()) {
				case PASS -> pass++;
				case FAIL -> {
					fail++;
					failures.add(task.name() + ": " + result.detail());
				}
				case SKIP -> skip++;
			}
		}
		System.out.println("=== 汇总：通过 " + pass + " / 失败 " + fail + " / 跳过 " + skip + " ===");
		if (!failures.isEmpty()) {
			System.out.println("失败明细：");
			failures.forEach(f -> System.out.println("- " + f));
		}
	}

	/**
	 * utils 无独立 main，内联轻量冒烟（无外部依赖）。
	 */
	private static SmokeResult smokeUtils() {
		System.out.println("[1/1] UtilsTools.currentDateTime()");
		String value = new UtilsTools().currentDateTime();
		System.out.println("      结果：" + value);
		return SmokeResult.of(value != null && !value.isBlank(), "结果：" + value);
	}

	/**
	 * 调度服务的独立 main：捕获其 stdout（步骤化输出），据"结论：通过 / 未设置，退出 /
	 * Executable doesn't exist"判定 PASS/FAIL/SKIP。
	 */
	private static SmokeResult callMain(Callable<?> main) {
		PrintStream original = System.out;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
			main.call();
		}
		catch (Exception e) {
			return SmokeResult.fail("异常：" + e.getMessage());
		}
		finally {
			System.setOut(original);
		}
		String out = buffer.toString(StandardCharsets.UTF_8);
		System.out.println(out.stripTrailing());
		if (out.contains("未设置，退出")) {
			return SmokeResult.skip("依赖未设置");
		}
		if (out.contains("结论：通过")) {
			return SmokeResult.pass("冒烟通过");
		}
		if (out.contains("Executable doesn't exist")) {
			return SmokeResult.skip("需先安装 chromium");
		}
		return SmokeResult.fail("冒烟未通过（见上方输出）");
	}

	// ---- 基础设施 ----

	private static SmokeResult run(SmokeTask task) {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			return pool.submit(task.body()).get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (TimeoutException e) {
			return SmokeResult.fail("超时（>" + TASK_TIMEOUT_SECONDS + "s）");
		}
		catch (Exception e) {
			return SmokeResult.fail("异常：" + e.getMessage());
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** 一个冒烟任务：名称 + 执行体（返回 PASS/FAIL/SKIP 判定）。 */
	record SmokeTask(String name, String description, Callable<SmokeResult> body) {
	}

	/** 冒烟判定：PASS 通过 / FAIL 失败 / SKIP 环境未就绪（不计入失败）。 */
	record SmokeResult(Status status, String detail) {

		enum Status {
			PASS, FAIL, SKIP
		}

		static SmokeResult of(boolean ok, String detail) {
			return ok ? new SmokeResult(Status.PASS, detail) : new SmokeResult(Status.FAIL, detail);
		}

		static SmokeResult pass(String detail) {
			return new SmokeResult(Status.PASS, detail);
		}

		static SmokeResult fail(String detail) {
			return new SmokeResult(Status.FAIL, detail);
		}

		static SmokeResult skip(String detail) {
			return new SmokeResult(Status.SKIP, detail);
		}

	}

}
