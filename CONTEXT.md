# XYZ MCP Hub — 上下文与领域模型

## 项目定位

**MCP 聚合门面（MCP Hub）**。对外暴露多个独立的 MCP Server 端点，内部通过 Native/Proxy 两种方式整合各类 MCP 工具。提供 Vaadin 管理界面用于未来配置管理。

## 领域词汇表

### 核心概念

| 术语 | 英文 | 定义 |
|---|---|---|
| MCP Hub | MCP Hub | 本项目的本体。对外暴露多个独立 MCP Server 端点，内部聚合并管理各类 MCP 服务 |
| MCP 端点 | McpEndpoint | Hub 对外暴露的一个独立 MCP Server。每个端点在独立的 URL 路径下，LLM 客户端按需连接 |
| 端点提供者 | McpEndpointProvider | SPI 接口。每个 MCP 服务模块实现此接口，声明名称、路径、scope 及工具列表，`HubMcpRegistrar` 自动发现并注册 |
| MCP 端点注册器 | HubMcpRegistrar | Hub 启动时自动发现所有 `McpEndpointProvider`，为每个创建独立的 `McpServer` + `TransportProvider` + `RouterFunction`，替代 Spring AI 默认的单端点自动配置 |

### 暴露方式（两个正交维度）

| 维度 | 分类 | 描述 |
|---|---|---|
| 实现维度 | NativeMcp / ProxyMcp / HostMcp | 工具从哪来（如何产生） |
| 使用维度 | builtin / config / custom | 端点如何被定义（由谁、以什么方式） |

#### 使用维度命名空间

| 术语 | URL | 定义 |
|---|---|---|
| 内置端点 | `/mcp/builtin/{name}` | 随 jar 内置、源码注册的端点，改代码才能变 |
| 配置端点 | `/mcp/config/{name}` | 来自 `mcp.spaces` 配置的组合端点，运维改配置即可调 |
| 自定义端点 | `/mcp/custom/{uuid}` | 用户经 UI 运行时创建的组合端点（未来实现） |

### 组合端点（Space）

| 术语 | 英文 | 定义 |
|---|---|---|
| 空间 | Space | 一个可命名的组合工具集：从多个已有端点引用工具（含整端点拉入），聚合成独立端点。使用维度的 config/custom 两种形态的载体 |
| 空间定义 | SpaceDefinition | Space 的纯数据 VO（name、path、来源列表）。按可持久化、可被 UI 编辑的形状设计 |
| 空间定义源 | SpaceDefinitionSource | SPI 接口，产出 `List<SpaceDefinition>`。实现有 YAML（读 `mcp.spaces`）与未来 DB 两种 |
| 组合引用 | SpaceSource | Space 的一个来源：`source`（源端点）+ `include`/`exclude`（精确工具名列表）。两列表空 = 整端点拉入 |

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
| 自定义端点 🔮 | CustomEndpoint | 用户经 UI 运行时创建组合 Space，暴露于 `/mcp/custom/{uuid}`。复用 `SpaceDefinitionSource` SPI 与 `SpaceDefinition` VO（DB 来源），UI 编辑同一对象。未来实现 |
| 模式匹配 🔮 | 无 | Space 组合引用的 `include`/`exclude` 支持通配符，替代精确枚举。未来按需实现 |

---

## 架构决策

详见 `docs/adr/`：

- `docs/adr/0001-multi-mcp-server-endpoints.md` — 绕过 Spring AI 自动配置，手动多端点
- `docs/adr/0002-single-module-jpms.md` — 单 Maven 模块 + JPMS 模块化
- `docs/adr/0003-native-vs-proxy-mcp.md` — NativeMcp 为主，ProxyMcp 仅用于公有云 MCP
- `docs/adr/0004-spring-modulith-verification.md` — 使用 Spring Modulith 验证模块结构
- `docs/adr/0005-configuration-strategy.md` — 配置归入 application.yml + 敏感值环境变量分层注入（缺配置不注册）
- `docs/adr/0006-jpms-blocked-upstream.md` — JPMS 暂缓：上游 MCP SDK 非法模块名（issue #3）
- `docs/adr/0007-proxy-http-only-config-driven.md` — Proxy 转发：仅远程 HTTP、配置驱动认证、工具列表由提供者固定
- `docs/adr/0008-composed-space-config.md` — 组合端点 Space：使用维度命名空间（builtin/config/custom）、`mcp.spaces` 配置、`SpaceDefinitionSource` SPI

---

## 包结构

```
io.xyz.xyz_mcp_hub                          ← 根包
├── XyzMcpHubApplication.java               ← @SpringBootApplication
│
├── mcp                                     ← 模块 1 API 包（对外）
│   ├── McpEndpointProvider.java            ← SPI 接口
│   ├── Scope.java                          ← HOST / NETWORK
│   ├── SpaceDefinition.java                ← 组合端点 Space 的 VO（name/path/sources）
│   ├── SpaceSource.java                    ← 组合引用 VO（source + include/exclude）
│   ├── SpaceDefinitionSource.java          ← 空间定义源 SPI（List<SpaceDefinition> load()）
│   └── package-info.java                   ← @NamedInterface("api")
│
├── mcp.internal                            ← 以下全部不对外
│   ├── HubMcpRegistrar.java                ← 多端点注册器
│   ├── mcp.internal.nativemcp              ← NativeMcp 基类
│   │   ├── mcp.internal.nativemcp.host                 ← HostMcp（预留）
│   │   ├── mcp.internal.nativemcp.network.utils        ← UtilsMcpProvider + UtilsTools
│   │   └── mcp.internal.nativemcp.network.bocha        ← BochaMcpProvider + BochaTools（博查搜索）
│   ├── mcp.internal.proxy                  ← ProxyMcpProvider + ProxyInterceptor（预留）
│   └── mcp.internal.space                  ← Space 组合端点实现（SpaceMcpProvider / SpaceEndpointRegistrar / YamlSpaceDefinitionSource / SpaceToolMaterializer）
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

- `application.yml` — 主配置（含 `spring.config.import`、`mcp.spaces` 组合端点定义等）
- `application-local.yml` — 本地敏感配置（API key/token，`.gitignore` 排除）
- `spring.profiles.active: local`

内置端点由代码中的 `McpEndpointProvider` 实现自注册，API key 等参数从 Spring 标准配置体系注入；组合端点 Space 由 `mcp.spaces` 配置声明，经 `SpaceDefinitionSource` SPI 加载（见 ADR-0008）。

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
