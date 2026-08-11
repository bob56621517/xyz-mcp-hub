# ADR-0007: Proxy 转发 — 仅远程 HTTP、配置驱动（通用转发器 + hook）

Hub 作为 MCP Client 透明代理上游公有云 MCP Server（GitHub、context7、grep.app、Wikidata）时，**只支持远程 HTTP（Streamable HTTP）转发，永不用 stdio 子进程**；proxy 源由 `mcp.proxies` 配置声明 + 一个通用转发器建源（2026-08-11 修订：不再逐个写 Provider 类）；上游认证字段经 Spring Boot 配置（`${...}` 占位符 + 环境变量 / `application-local.yml`，见 ADR-0005）注入固定 header；工具子集作为配置项 / hook 扩展。

## 状态

已接受（2026-08-11 修订：配置驱动注册）

## 决策

1. **仅远程 HTTP 传输**：Proxy 基础设施只实现远程 HTTP（Streamable HTTP）转发。**永不用 stdio 子进程**（项目铁则）——不接受以 spawn `gh mcp`、npx、docker 等本地进程方式接入上游。
2. **配置驱动注册（通用转发器）**：proxy 源不再逐个写 Provider 类，统一由 `mcp.proxies` 配置声明 + 一个通用转发器建源：

   ```yaml
   mcp:
     proxies:
       - name: github
         upstream-url: https://api.githubcopilot.com/mcp/
         # 完整认证 header（如 "Authorization: Bearer <token>"，经 GITHUB_AUTH_HEADER 注入）；
         # 留空（未设置）→ 源未启用（注册/启用分离）
         auth-header: "${GITHUB_AUTH_HEADER:}"
         tools-subset: []                        # 可选：固定工具子集
   ```

   通用转发器从配置建源（name / upstream-url / auth-header / 可选工具子集 / enabled 门控），内部仍走启动时 `listTools` 发现 + `callTool` 透传。
3. **hook 可扩展点**：特殊代理需求（自定义认证构造、工具子集过滤、错误处理、工具名映射）经转发器保留的 hook 回调扩展，不新增 Provider 类。
4. **具体代理只在冒烟测**：真实代理（context7/github 等）的联通性在冒烟测试验证（需 token/网络）；机制层用测试替身（`TestProxyMcpProvider`）验证通用转发器本身。
5. **本地 HTTP 暴露**：第一版 Hub 是本地运行的、对外以 HTTP 暴露 MCP 端点的聚合服务。

## 考虑过的选项

- **stdio 子进程**（gh mcp / npx / docker）——被用户以"项目铁则"明确否决，因其引入子进程生命周期管理与可移植性问题。
- **通用工具过滤机制（旧决策 3）**——原被否决；配置化后工具子集收敛为 `tools-subset` 配置 + hook，仍不做通用黑白名单机制。

## 后果

- 只有提供了远程 HTTP MCP 端点的公有云服务可接入；仅有本地 npm/二进制实现（无远程端点）的服务不可接入。
- 新增一个代理服务 = 在 `mcp.proxies` 配置加一行 + 认证字段，无需写代码；机制层测试用 `TestProxyMcpProvider` 覆盖通用转发器。
