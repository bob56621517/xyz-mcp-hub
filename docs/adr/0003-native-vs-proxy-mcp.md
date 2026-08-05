# ADR-0003: NativeMcp 为主，ProxyMcp 仅用于公有云 MCP

## 日期

2026-08-05

## 状态

已接受

## 背景

XYZ MCP Hub 需要整合多种来源的 MCP 工具。存在两种获取方式：
- **NativeMcp**：在 Hub 的 JVM 中重新实现，直接调用第三方 HTTP API
- **ProxyMcp**：作为 MCP Client 连接外部已有的 MCP Server，透明代理转发

## 决策

**NativeMcp 为默认选择，ProxyMcp 仅用于已有官方公有云 MCP 端点的服务。**

核心原则：本项目存在的意义 = 用自己的方式重新实现第三方服务，而不是转发别人写的 MCP Server。

**例外**：如果某个服务已经提供了官方公有云 MCP 端点（如 `https://wd-mcp.wmcloud.org/mcp/`），才通过 ProxyMcp 透明代理。因为这种情况下重复实现的成本高于收益。

### 分类

```java
// NativeMcp 的子类型
Scope.HOST     → HostMcp  — 必须与 CLI 同主机（当前预留）
Scope.NETWORK  → NetworkMcp — 纯网络调用（如 utils、bocha）

// ProxyMcp
Scope.NETWORK  → 恒为 NetworkMcp — 代理外部 MCP Server
```

### ProxyMcp 设计

- **纯透明代理**：Hub 连接外部 MCP Server，原样暴露其工具列表，调用时直接转发
- **ProxyInterceptor**：提供 `onBefore(chain)` / `onAfter(chain)` 钩子，当前预留不实现
- 无论是否需要拦截器，代码路径统一经过拦截器链（当前链为空）

## 后果

- **正面**：代码风格统一，所有工具实现在 Hub 内可控
- **正面**：不依赖第三方 MCP Server 的可用性（NativeMcp 自己保证）
- **负面**：需为每个第三方 API 单独编写适配代码（但这是项目存在的意义）
