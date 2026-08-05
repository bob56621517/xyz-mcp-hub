# ADR-0005: 配置策略 — 不引入独立配置文件

## 日期

2026-08-05

## 状态

已接受

## 背景

项目需要管理多个 MCP 服务的配置（API key、端点路径等）。曾考虑引入独立的 `mcp-config.yml` 文件。

## 决策

**不引入独立配置文件，所有配置归入 Spring Boot 标准配置体系。**

理由：
- MCP 端点由 `McpEndpointProvider` 实现类自注册，不需要配置文件声明
- 端点无需"开关"——一个 MCP 服务是否存在，由 LLM 客户端是否配置连接它决定
- API key 等敏感配置通过 `application-local.yml` 管理

### 配置文件职责

| 文件 | 内容 | 版本控制 |
|---|---|---|
| `application.yml` | 主配置：框架参数、端点路径覆盖（如有需要）、`spring.profiles.active: local` | ✅ 提交 |
| `application-local.yml` | 本地敏感信息：API key、token 等 | ❌ `.gitignore` |

### 各 MCP 模块的配置注入

每个 `McpEndpointProvider` 通过 Spring 标准机制获取自身需要的 `@Value` 或 `@ConfigurationProperties`，不依赖集中式的 MCP 配置文件。

## 后果

- **正面**：配置文件数量最少化
- **正面**：配置约定遵循 Spring Boot 标准
- **正面**：敏感信息隔离在 `application-local.yml`，从不出现在 Git 中
- **负面**：如果未来需要 UI 动态配置 MCP 服务（替代代码自注册），则需重新考虑
