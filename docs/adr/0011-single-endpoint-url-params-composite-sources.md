# ADR-0011: 单端点 + URL 参数选工具 + 目录 API

## 日期

2026-08-09（2026-08-11 修订：组合源退役打回草稿、URL 通配符语义、目录 enabled/type 收敛、proxy 配置化见 ADR-0007）

## 状态

已接受（2026-08-11 修订；**取代 ADR-0008 的组合端点 Space 命名空间**；组合源章节已退役）

## 背景

旧架构暴露**多端点**：`HubMcpRegistrar` 为每个 provider 注册独立路径（`/mcp/builtin/playwright`、`/mcp/server/github-full`、`/mcp/config/orders`…），组合端点由配置 Space（ADR-0008）以独立 URL 表达。

MCP 的 Streamable HTTP 端点 URL **原生支持 query param**——工具选择完全可以由连接 URL 表达，无需再维护一套命名端点注册机制（「URL 原生能过滤，就不造轮子」）。工具集是连接级的（`initialize`/`listTools` 时确定），每个 client 在自己的配置里写不同 URL，各看各的子集，零 Hub 侧配置，正好命中「按需暴露、省 Token」的价值。

## 决策

**端点表面收敛为单路径 + URL 参数；组合源（specs）发布新源入目录；提供目录 API。**

### 单端点与双传输

- `/xyz-hub/mcp` — Streamable HTTP（MCP 2.0 标准）
- `/xyz-hub/sse` — 遗留 HTTP+SSE（兼容旧 client）
- **双传输默认开启**，挂同一 `McpServer`，共享同一套工具注册与过滤逻辑（过滤是应用层，与传输无关）。命名按传输语义（`/mcp` 与 `/sse` 为生态惯例）。

### 工具视图（连接级）

- **工具永远注册在源里**；`listTools` 时按连接 URL 参数过滤返回子集，被过滤的工具对 agent「不存在」——这正是省 Token 的机制。
- 实现为**单 McpServer + 按请求/会话解析 URL 参数**（无状态友好，因为 URL 参数在每个请求里都携带）。

### URL 参数语法（2026-08-11 修订：严格、无语法糖、通配符）

```
/xyz-hub/mcp?includes=[bocha*,*search]&excludes=[]
```

- `includes`/`excludes`（复数）：`includes` 先选（并集），`excludes` 再减。**无 `includes` ≡ `[*]`（全量）；`includes=[]` = 空集**（不引入任何工具，无语法糖——显式空 ≠ 全量）；无 `excludes` ≡ `[]`（不减）。
- 项 = **工具名**（下划线平坦名，如 `bocha_web_search`）。**源名匹配已退役**——要某源全部工具写 `bocha*`。
- **通配符**：工具名支持 `*`（裸 `*` = 全量 / `bocha*` 前缀 / `*search` 后缀 / `bo*search` 中间）。**不支持 `?`**。`*` 在 URL query 中合法、无需编码。
- 列表：URL 用 `[a,b]` 方括号。**统一用下划线，无点分隔**（MCP 工具名规范不允许点，暴露名与语法名同一套体系，零映射）。
- 未知项：静默忽略 + 日志 warn（不使连接失败）。

### 组合源（specs）— 已退役，打回草稿（2026-08-11）

- `mcp.specs` 组合源机制**整体移除**（#33 实现后评估不足：唯一用例 `github-readonly` 定位反复、快捷参数价值存疑、与"源"一等公民语义冲突）。
- 代码彻底删除，**保留 issue 记录为将来要做的功能（草稿）**：白名单搜索工具集、URL 快捷参数、`github-readonly` 定位等，待重做时再评估。
- 目录不再有 `type=composite`、不再有 `base` 溯源字段。

### 目录 API

- `GET /xyz-hub/catalog` — 机器可读的「源 + 工具」清单，URL 构建器与客户端的事实源。
- 每个源：`name` / `type`（native/proxy/container，host 并入 native 靠 scope 区分）/ `protocol`（container 专有：mcp|rest）/ `scope` / **`enabled`**（注册/启用分离，见 ADR-0005）/ `tools`（未启用源为空）。
- 目录列出**所有已注册源**（编译期/配置固定），`enabled` 反映配置门控；不再有 `base`/composite。
- 数据**三源汇合**：本地工具类声明（native/host）+ 静态冒烟数据（容器 mcp）+ 启动发现（配置 proxy，见 ADR-0007）。
- 默认无认证、仅本地可读（与 MCP 端点一致）。
- web 页 URL 构建器（勾选源/工具 → 生成 URL）延后决策（是否 Vaadin 未定）。

### 迁移

- 旧 `/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*` 端点**干净断掉**（彻底重构，无兼容性保证）。所有工具由 `/xyz-hub/mcp?includes=` 暴露。
- `HubMcpRegistrar` 从「每 provider 一个 McpServer」重构成「单 McpServer + 源注册表 + 每请求/会话工具视图」。
- **（#39 已实现）**：旧多端点机制（`HubMcpRegistrar`）与 Space 组合端点实现整体删除，旧路径 HTTP 404、无重定向；`McpEndpointProvider#getPath()` 一并移除。仅剩 `/xyz-hub/mcp` + `/xyz-hub/sse` + `/xyz-hub/catalog`。

## 后果

- **正面**：端点表面单一、可组合、零配置；URL 即工具视图；省 Token 价值直接由 client 配置达成；目录 API 让任意客户端可枚举、按需拼 URL。
- **正面**：过滤是应用层，双传输共享实现；通配符让工具选择表达力强且严格（源名退役、`includes=[]` 空集语义明确）。
- **负面**：`includes=[]` 是空集而非全量（严格语义，需文档化）；懒 client 无参数仍会拿到全部工具（省 Token 靠自觉）；组合源退役后无"多源聚合"能力（打回草稿，见 issue）。
