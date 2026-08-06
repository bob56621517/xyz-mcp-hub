# ADR-0005: 配置策略 — 不引入独立配置文件，敏感值经环境变量分层注入

## 日期

2026-08-05（2026-08-06 修订：敏感值分层 + 缺配置不注册 + .example 模板）

## 状态

已接受（修订）

## 背景

项目需要管理多个 MCP 服务的配置（API key、端点路径等）。曾考虑引入独立的 `mcp-config.yml` 文件；初版决策将敏感值放 `application-local.yml`（.gitignore）。实际落地时该文件从未被创建，且为并行开发三个新端点（#4/#5/#6）做准备，需要统一的敏感值注入契约与"缺配置优雅降级"机制。

## 决策

**不引入独立配置文件，所有配置归入 Spring Boot 标准配置体系；敏感值按"占位符固定 + 环境变量注入"分层。**

理由：
- MCP 端点由 `McpEndpointProvider` 实现类自注册，不需要配置文件声明
- 端点无需"开关"——是否注册由该服务是否具备必要配置决定（缺配置不注册，见下）
- 敏感值（API key/token）以全大写环境变量注入，程序内用 kebab 小写名经 `@Value` 读取

### 配置分层

| 层 | 文件/位置 | 内容 | 版本控制 |
|---|---|---|---|
| 引用层 | `application.yml` | 固定占位符，如 `bocha.api-key: ${BOCHA_API_KEY:}` | ✅ 提交 |
| 值层 | 环境变量 或 `application-local.yml` | `BOCHA_API_KEY: <真实值>` | ❌ local.yml .gitignore |
| 模板 | `application-local.yml.example` | 列出全部需注入的 key 名（值留空） | ✅ 提交 |

值可放环境变量（部署注入）或 `application-local.yml`（本地集中），程序无感知；`${BOCHA_API_KEY:}` 空默认，缺省时对应端点不注册。

### 缺配置不注册

`McpEndpointProvider` 提供 `isEnabled()`（默认 `true`）。某 MCP 服务缺少必要配置（如 bocha 无 api-key）时返回 `false`，`HubMcpRegistrar` 跳过注册。proxy 上游连接失败由注册器在 connect 时兜底，跳过该端点并记录失败日志，应用照常启动。

### 启动日志

每个 MCP 服务注册时记录 成功/跳过/失败 及原因，便于定位端点缺失原因。

## 后果

- **正面**：敏感值永不落库（环境变量）或仅落本地 .gitignore 文件
- **正面**：配置契约有 `application-local.yml.example` 模板可查
- **正面**：单个端点配置缺失或上游故障不拖垮整个 Hub
- **负面**：坏端点需重启才可重试（无运行时重连）
- **负面**：如未来需要 UI 动态配置 MCP 服务（替代代码自注册），则需重新考虑
