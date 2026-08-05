# XYZ MCP Hub — 上下文与领域模型

## 项目定位

**MCP 聚合门面（MCP Hub）**。对外暴露多个独立的 MCP Server 端点，内部通过 Native/Proxy 两种方式整合各类 MCP 工具。提供 Vaadin 管理界面用于未来配置管理。

## 领域词汇表

### 核心概念

| 术语 | 英文 | 定义 |
|---|---|---|
| MCP Hub | MCP Hub | 本项目的本体。对外暴露多个独立 MCP Server 端点，内部聚合并管理各类 MCP 服务 |
| MCP 端点 | McpEndpoint | Hub 对外暴露的一个独立 MCP Server。每个端点在独立的 URL 路径下（如 `/mcp/server/utils`），LLM 客户端按需连接 |
| 端点提供者 | McpEndpointProvider | SPI 接口。每个 MCP 服务模块实现此接口，声明名称、路径、scope 及工具列表，`HubMcpRegistrar` 自动发现并注册 |
| MCP 端点注册器 | HubMcpRegistrar | Hub 启动时自动发现所有 `McpEndpointProvider`，为每个创建独立的 `McpServer` + `TransportProvider` + `RouterFunction`，替代 Spring AI 默认的单端点自动配置 |

### MCP 实现类型

| 术语 | 英文 | 定义 |
|---|---|---|
| 原生 MCP | NativeMcp | Hub 在自己的 JVM 中重新实现的外部服务。直接调用第三方 HTTP API，不使用外部已有的 MCP Server 实现 |
| 代理 MCP | ProxyMcp | Hub 作为 MCP Client 透明代理已有官方公有云 MCP Server。仅支持远程 HTTP（Streamable HTTP）传输，不用 stdio 子进程；认证字段经 Spring Boot 配置注入固定 header；暴露的工具列表由提供者代码固定（见 ADR-0007） |
| 拦截器 | ProxyInterceptor | ProxyMcp 的扩展点。提供 `onBefore` 和 `onAfter` 默认方法，用于日志记录、速率限制等增强功能。（当前预留，不实现） |

### 部署范围

| 术语 | 英文 | 定义 |
|---|---|---|
| 主机 MCP | HostMcp | 必须部署在 Agent/CLI 同主机的 MCP 服务（如文件系统操作）。NativeMcp 的子类。当前预留字段，不实现运行时检查 |
| 网络 MCP | NetworkMcp | 通过网络可达即可，对部署位置无约束。NativeMcp 和 ProxyMcp 均可属于此类 |
| 范围 | Scope | 枚举：`HOST` / `NETWORK`。记录在 `McpEndpointProvider` 中，当前为预留标记 |

### 未来规划

| 术语 | 英文 | 定义 |
|---|---|---|
| 聚合端点 🔮 | AggregatedEndpoint | 用户自定义的工具级组合端点，从多个 MCP 服务中挑选工具合并为一个新端点。未来实现 |

---

## 架构决策

详见 `docs/adr/`：

- `docs/adr/0001-multi-mcp-server-endpoints.md` — 绕过 Spring AI 自动配置，手动多端点
- `docs/adr/0002-single-module-jpms.md` — 单 Maven 模块 + JPMS 模块化
- `docs/adr/0003-native-vs-proxy-mcp.md` — NativeMcp 为主，ProxyMcp 仅用于公有云 MCP
- `docs/adr/0004-spring-modulith-verification.md` — 使用 Spring Modulith 验证模块结构
- `docs/adr/0005-configuration-strategy.md` — 配置归入 application.yml + application-local.yml（敏感信息）
- `docs/adr/0006-jpms-blocked-upstream.md` — JPMS 暂缓：上游 MCP SDK 非法模块名（issue #3）
- `docs/adr/0007-proxy-http-only-config-driven.md` — Proxy 转发：仅远程 HTTP、配置驱动认证、工具列表由提供者固定

---

## 包结构

```
io.xyz.xyz_mcp_hub                          ← 根包
├── XyzMcpHubApplication.java               ← @SpringBootApplication
│
├── mcp                                     ← 模块 1 API 包（对外）
│   ├── McpEndpointProvider.java            ← SPI 接口
│   ├── Scope.java                          ← HOST / NETWORK
│   └── package-info.java                   ← @NamedInterface("api")
│
├── mcp.internal                            ← 以下全部不对外
│   ├── HubMcpRegistrar.java                ← 多端点注册器
│   ├── mcp.internal.nativemcp              ← NativeMcp 基类
│   │   ├── mcp.internal.nativemcp.host                 ← HostMcp（预留）
│   │   ├── mcp.internal.nativemcp.network.utils        ← UtilsMcpProvider + UtilsTools
│   │   └── mcp.internal.nativemcp.network.bocha        ← BochaMcpProvider（桩，空工具）
│   └── mcp.internal.proxy                  ← ProxyMcpProvider + ProxyInterceptor（预留）
│
├── ui                                      ← 模块 2（Vaadin 管理界面）
│   └── ui.internal
│
└── (JPMS module-info.java 暂缓 —— 上游 MCP SDK 非法模块名阻塞，见 issue #3)
```

**说明**：`native` 是 Java 保留字，不能作包名段，故规范中的 `mcp.internal.native.*` 落地为 `mcp.internal.nativemcp.*`。

**Spring Modulith 模块边界**：
- `mcp` 模块 — API 包 `io.xyz.xyz_mcp_hub.mcp`，internal 嵌套子包自动不可访问
- `ui` 模块 — 当前无对外 API

---

## 配置约定

- `application.yml` — 主配置（含 `spring.config.import` 等）
- `application-local.yml` — 本地敏感配置（API key/token，`.gitignore` 排除）
- `spring.profiles.active: local`

不需要独立的 `mcp-config.yml`。MCP 端点由代码中的 `McpEndpointProvider` 实现自注册，API key 等参数从 Spring 标准配置体系注入。

---

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Java | 25 | 运行时 |
| Spring Boot | 4.1.0 | 应用框架 |
| Spring AI | 2.0.0 | MCP Server/Client SDK |
| Spring Modulith | 2.1.0 | 模块结构验证 |
| Vaadin | 25.2.5 | 管理 UI |
| SQLite | — | 运行时数据存储 |
| JPMS | — | 编译期模块隔离 + jlink 裁剪 JRE（暂缓，上游阻塞，见 issue #3） |
