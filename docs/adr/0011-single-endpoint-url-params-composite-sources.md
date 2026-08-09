# ADR-0011: 单端点 + URL 参数选工具 + 组合源 + 目录 API

## 日期

2026-08-09

## 状态

已接受（**取代 ADR-0008 的组合端点 Space 命名空间**）

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

### URL 参数语法（与 YAML 完全一致）

```
/xyz-hub/mcp?includes=[jina,bocha_web_search]&excludes=[]
```

- `includes`/`excludes`（复数）：`includes` 先选（并集），`excludes` 再减；**无参数 = 全量**（向后兼容）。
- 项 = **下划线平坦名**：源名（`jina` → 展开该源全部工具）或工具名（`bocha_web_search` → 精确一个工具）。解析：先精确匹配工具名，再按源名做 `{source}_` 前缀展开。
- 列表：URL 用 `[a,b]` 方括号（URL 不允许空格），YAML 用数组，两处一致。**统一用下划线，无点分隔**（MCP 工具名规范不允许点，暴露名与语法名同一套体系，零映射）。
- 未知项：静默忽略 + 日志 warn（不使连接失败）。

### 组合源（specs，取代 Space 组合端点）

- YAML 定义（`mcp.specs`），**发布成一个新源入目录**，与任何普通源同等被 `includes` 引用；URL 无关。
- 可引用多个源、支持 `includes`/`excludes`（含精确到工具）；**启动时静态解析、可嵌套、发布时循环检测**；动态性只来自 URL 过滤。
- 例：`github-readonly` = `{includes: [github], excludes: [github_create_issue, ...]}`。
- 旧 Space 组合端点（独立 URL 命名空间）退役；`SpaceDefinition`/`SpaceSource` VO 保留、`path` 字段退役。

### 目录 API

- `GET /xyz-hub/catalog` — 机器可读的「源 + 工具」清单，URL 构建器与客户端的事实源。
- 每个源：`name` / `type`（native/proxy/container/host/composite）/ `protocol`（container 专有：mcp|rest）/ `scope` / `tools`；组合源带 `base` + 过滤溯源。
- 数据**三源汇合**：代码声明（native/host/rest 包装）+ 静态冒烟数据（容器 mcp）+ 启动发现（公有云 proxy）。
- 默认无认证、仅本地可读（与 MCP 端点一致）。
- web 页 URL 构建器（勾选源/工具 → 生成 URL）延后决策（是否 Vaadin 未定）。

### 迁移

- 旧 `/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*` 端点**干净断掉**（彻底重构，无兼容性保证）。所有工具由 `/xyz-hub/mcp?includes=` 暴露。
- `HubMcpRegistrar` 从「每 provider 一个 McpServer」重构成「单 McpServer + 源注册表 + 每请求/会话工具视图」。

## 后果

- **正面**：端点表面单一、可组合、零配置；URL 即 Space；省 Token 价值直接由 client 配置达成；目录 API 让任意客户端可枚举、按需拼 URL。
- **正面**：过滤是应用层，双传输共享实现；语法 URL/YAML 一致，心智负担低。
- **负面**：无参数 = 全量，懒 client 会拿到全部工具（省 Token 靠自觉）；组合源静态解析，base 源变化需重启；重构工作量大（`HubMcpRegistrar` 重写 + 组合源注册 + 目录端点）。
