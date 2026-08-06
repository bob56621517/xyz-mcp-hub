# 并行交接：3 个 MCP 议题

准备议题 #9（敏感配置环境变量化 + 端点优雅降级）已合入 main（708f5f4），
#4 / #5 / #6 解除阻塞，可从 main 并行开工。

## 三个工作区（worktree）

| 目录 | 分支 | 议题 |
|---|---|---|
| `../xyz-mcp-hub-issue4` | `feat/issue-4-playwright` | #4 集成 playwright 浏览器自动化 |
| `../xyz-mcp-hub-issue5` | `feat/issue-5-github-mcp` | #5 集成 GitHub MCP（转发） |
| `../xyz-mcp-hub-issue6` | `feat/issue-6-context7` | #6 集成 context7 / grep.app / wikidata |

每个 worktree 已检出含准备议题的 main，文件系统隔离、独立分支、互不冲突。
开工前先 `git pull origin main`（确保拿到本交接文档）。

## 共享框架文件已冻结

以下文件在准备议题完成，**三个议题都不要修改**，只新增各自包下独立文件：
`HubMcpRegistrar`、`McpEndpointProvider`、`application.yaml`、`pom.xml`、
`application-local.yml.example`、`docs/adr/`。

## 每个议题的要点

### #4 playwright（NativeMcp，无外部 key）
- 端点 `/mcp/server/playwright`；新包 `mcp.internal.nativemcp.network.playwright`
- `PlaywrightMcpProvider extends NativeMcp`（Scope.NETWORK）+ `PlaywrightTools`
- 按官方 playwright MCP 工具集逐个实现（navigate/snapshot/click/type/screenshot/…）
- **pom 已加 playwright 1.62.0，勿再改 pom**
- 注意：Playwright Java API 是异步 CompletableFuture，@Tool 内需妥善等待
- 测试：先安装 chromium 二进制（README 记录），无头打开本地页 snapshot/screenshot

### #5 GitHub（ProxyMcp，需 token）
- 端点 `/mcp/server/github-full`（全量）+ `/mcp/server/github-readonly`（固定只读清单）
- 两个 `ProxyMcpProvider` 子类；read-only 用 `getToolNames()` 固定只读工具
- 认证：GitHub 远程托管 MCP URL + Bearer token → 在 `application.yaml` 加
  `${GITHUB_TOKEN:}` 占位符 + `application-local.yml.example` 加 `GITHUB_TOKEN:`，
  本地值进 `application-local.yml`；`isEnabled()` 按 token 非空
- 测试：仿 `McpProxyEndpointTest`（内嵌上游）或 `McpGracefulDegradationTest`

### #6 context7 / grep.app / wikidata（ProxyMcp，一般免认证）
- 端点 `/mcp/server/context7`、`/mcp/server/grep-app`、`/mcp/server/wikidata`
- 上游 URL：`https://mcp.context7.com/mcp`、`https://mcp.grep.app`、
  `https://wd-mcp.wmcloud.org/mcp`
- 三个 `ProxyMcpProvider` 子类；如需认证按 #5 的占位符方式
- bocha Native 部分已完成（b109e43），**本议题不再做 bocha**
- 测试：仿 `McpProxyEndpointTest` 内嵌上游 mock

## 公共约定

- 测试命令：`./mvnw test -Dvaadin.skip=true`（跳过 Vaadin 前端构建，快）
- **测试编写遵循 `docs/testing/mcp-service-test-guide.md`**（NativeMcp 逐工具真实测试 / ProxyMcp mock 联通 + 手工冒烟；测试类 Javadoc 声明 `@requires-*` 外部依赖）
- 启动时 `HubMcpRegistrar` 对每个端点输出 成功/跳过/失败 日志，可据此调试
- 缺必要配置的端点自动不注册（`isEnabled()`），不会拖垮应用
- 完成：各自提交 → `git push` → 合并回 main → close issue；手工冒烟结果作为评论加载议题
