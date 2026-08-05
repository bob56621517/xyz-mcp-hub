# ADR-0001: 绕过 Spring AI 自动配置，手动多 MCP Server 端点

## 日期

2026-08-05

## 状态

已接受

## 背景

Spring AI 2.0 的 `McpServerStreamableHttpWebMvcAutoConfiguration` 自动配置只支持**单个 MCP Server 端点**。所有 `ToolCallbackProvider` bean 被合并暴露在同一个 `/mcp` 端点下。

但 XYZ MCP Hub 的核心需求是**多个独立端点**——每个内置/代理 MCP 服务有独立 URL（如 `/mcp/server/utils`、`/mcp/server/bocha`），LLM 客户端按需选择连接。

## 决策

**绕过 Spring AI 自动配置，手动实现多端点注册。**

具体做法：

1. 排除 `McpServerStreamableHttpWebMvcAutoConfiguration`
2. 实现 `HubMcpRegistrar`：收集所有 `McpEndpointProvider` bean
3. 对每个 provider，创建独立的 `WebMvcStreamableServerTransportProvider(mcpEndpoint=path)` + `McpServer.sync(transport).tools(provider.tools()).build()`
4. 每个 transport 的 `getRouterFunction()` 作为独立的 `RouterFunction` bean 注册到 Spring MVC（Spring MVC 原生支持多 RouterFunction 合并）
5. 维护所有 McpServer 的生命周期管理

### 可行性验证

- `WebMvcStreamableServerTransportProvider.builder().mcpEndpoint(path)` 支持自定义端点路径
- `RouterFunction` 在 Spring MVC 中支持多实例共存，框架自动合并路由表
- 参考实现：Spring AI 自身的 `McpServerStreamableHttpWebMvcAutoConfiguration`（本质上就是把它的单实例逻辑改为循环）

## 后果

- **正面**：实现独立端点，每个端点可独立启停、独立工具集
- **正面**：仍复用 Spring AI 的 `WebMvcStreamableServerTransportProvider`（成熟的 HTTP transport 实现）
- **负面**：不依赖 Spring AI 自动配置，后续版本升级时需关注内部 API 变化
- **负面**：`HubMcpRegistrar` 需要自行处理 McpServer 生命周期（启动/优雅关闭）
