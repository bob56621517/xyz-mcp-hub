# ADR-0007: Proxy 转发基础设施仅远程 HTTP，认证配置驱动，工具列表由提供者固定

Hub 作为 MCP Client 透明代理上游公有云 MCP Server（GitHub、context7、grep.app、Wikidata）时，**只支持远程 HTTP（Streamable HTTP）转发，永不用 stdio 子进程**；上游认证字段一律经 Spring Boot 配置（`application-local.yml`）注入固定 header；不做通用工具过滤机制，每个 ProxyMcp 暴露哪些工具由提供者代码固定。

## 状态

已接受

## 决策

1. **仅远程 HTTP 传输**：Proxy 基础设施只实现远程 HTTP（Streamable HTTP）转发。**永不用 stdio 子进程**（项目铁则）——不接受以 spawn `gh mcp`、npx、docker 等本地进程方式接入上游。
2. **配置驱动的认证**：上游需要的认证字段（如 Bearer token）全部通过 Spring Boot 配置注入，作为固定 header 字段发送；敏感值进 `application-local.yml`（见 ADR-0005）。
3. **不做通用工具过滤**：`read-only` 等工具子集需求由提供者代码固定一个定制工具列表，基础设施不实现黑白名单过滤机制。
4. **本地 HTTP 暴露**：第一版 Hub 是本地运行的、对外以 HTTP 暴露 MCP 端点的聚合服务。

## 考虑过的选项

- **stdio 子进程**（gh mcp / npx / docker）——被用户以"项目铁则"明确否决，因其引入子进程生命周期管理与可移植性问题。
- **通用工具过滤机制**——被否决：为 #5 read-only 一个需求引入通用机制属过度设计，提供者代码固定列表更简单。

## 后果

- 只有提供了远程 HTTP MCP 端点的公有云服务可接入；仅有本地 npm/二进制实现（无远程端点）的服务不可接入。
- 新增一个代理服务 = 新增一个 Provider + 配置认证字段，无基础设施改动。
