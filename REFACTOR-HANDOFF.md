# 重构交接：docker 运行时化 + 单端点收敛

> **本交接供未来对话读取**：架构方向与全部共识已定（ADR-0009~0012 + CONTEXT.md），
> 具体规划与任务拆解**留给新对话做**（本对话只产文档、不写代码、不拆任务）。

## 一句话

把项目从「JVM 内重实现引擎的多端点 MCP」重构为「**docker 运行时化 + 单端点 URL 选工具**」：四类 MCP、薄实现原则、fetch/content 全部退役、目录 API 先行。

## 已定架构（共识全录）

### 四类 MCP（ADR-0009）

| 类型 | 形态 | 薄/例外 |
|---|---|---|
| ProxyMcp | 转发公有云 HTTP MCP；工具**启动时发现** | 薄 |
| NativeMcp | JVM 内包装 HTTP API/SDK（bocha）；不重造引擎 | 薄 |
| ContainerMcp | 本地容器按需拉起→接入；`protocol: mcp`（转发）/ `rest`（薄包装） | 薄 |
| HostMcp | 同宿主（文件/IM/真实浏览器交互如 playwright）；**例外**，仍用官方 SDK | 例外 |

- **薄实现原则**：能力若已有成熟第三方 MCP（或可容器化），就转发/拉容器，绝不 JVM 重造。
- **工具清单来源**：谁控制变更谁静态——公有云 proxy 启动时发现；容器 mcp（镜像 pin）静态冒烟；rest 包装/native/host 代码声明。
- **SSRF**（ADR-0010）：复用现有 `SsrUrlGuard` 迁至 `security` 包；容器代抓用 `check(url)` 预检 + 容器隔离兜底。

### 端点（ADR-0011）

- 单端点：`/xyz-hub/mcp`（Streamable HTTP）+ `/xyz-hub/sse`（HTTP+SSE），**双传输默认开**，共享过滤。
- `?includes=[jina*,bocha_web_search]&excludes=[]`——**下划线平坦工具名**，支持 `*` 通配（裸 `*`=全量；源名匹配已退役，#51），URL/YAML 一致，无 includes=全量、显式 `includes=[]`=空集、无 excludes=不减，未知项静默忽略。
- 工具视图 = 单 McpServer + 每请求/会话按 URL 参数过滤 `listTools`（工具永远注册）。
- **组合源（specs）**：**已退役（#49）**——`mcp.specs` 组合源机制整体移除（唯一用例 `github-readonly` 定位反复、价值存疑），代码彻底删除、issue #47 打回草稿；目录不再有 `type=composite` / `base` 溯源（见 ADR-0011 修订）。
- **目录 API** `GET /xyz-hub/catalog`（先行）；web 页 URL 构建器延后（Vaadin 未定）。
- 旧 `/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*` **干净断掉**（彻底重构，无兼容保证）。

### 分发/仓库（ADR-0012）

- 多模块 Maven，Maven = 最终打包入口。
- `hub/`（标准 Spring Boot，**不进 docker**，`java -jar` 直启）+ `sidecars/{markitdown,playwright}`（目标 = Dockerfile/镜像）。
- `mvn install` 只构建 + 安装 **sidecar 镜像到本地 docker**；`manifests/mcp-images.yaml` = mvn 生成的运行规范（image/protocol/port）。
- `docker` 顶级模块（与 playwright 同级）管容器生命周期：首用拉起 + 闲置回收。
- `compose.yaml` 可选，仅起 hub；分发 #2（Docker Hub 发布）暂缓；远端/云部署不做。

## 已合入工作的连锁作废

| 议题/组件 | 处置 |
|---|---|
| #26 fetch 门面 markitdown 化 | **作废**（fetch 已砍） |
| #25 markitdown 本地子进程（MarkitdownServer） | **作废**（markitdown 容器源，不再子进程） |
| #24/#22 content 引擎（ConvertEngine/FormatConverter/格式路由） | **整体退役**（content 顶级模块删除） |
| HtmlToMarkdown / PdfTextExtractor / Readability / 分块 / playwright 渲染编排 | **全部退役** |
| Space 组合端点（ADR-0008） | 改造为「组合源」（specs）注册 → 组合源也已退役（#49），见 ADR-0011 修订 |
| 多端点 HubMcpRegistrar | 重写为「单 McpServer + 源注册表 + 工具视图」 |
| ADR-0003（NativeMcp 为主） | 被 ADR-0009 取代 |
| playwright 引擎 | **保留**（HostMcp 场景） |

## 未决 / 延后（未来对话处理）

- **任务拆解与议题创建**：本对话未建 issue；未来对话按标准工作流（gh CLI、`ready-for-agent` 标签、测试规范）拆任务。注意：**已移除「[草稿]」前缀临时约定**，按常规流程走。
- web 页 URL 构建器（是否 Vaadin）——延后。
- 分发 #2（Docker Hub 发布 + 跨架构 amd64/arm64）——暂缓。
- ProxyMcp 工具清单周期刷新（TTL）——可选未来项。
- `SsrUrlGuard` Javadoc 中「ADR-0010 决策 6」引用——**已被本批 ADR-0010 兑现**，迁移到 `security` 包时核对。

## 文档索引

- `CONTEXT.md` — 领域词汇表（四类 MCP、源/目录/清单、工具选择语法、包结构；组合源已退役）
- `docs/adr/0009-docker-runtime-four-mcp-types.md` — 四类 MCP + 薄实现原则
- `docs/adr/0010-security-ssrf-guard.md` — SSRF 防护边界
- `docs/adr/0011-single-endpoint-url-params-composite-sources.md` — 端点收敛 + 目录（2026-08-11 修订：组合源退役）
- `docs/adr/0012-distribution-multimodule-scope.md` — 分发与仓库结构
