# MCP 服务测试用例生成指南

每个 MCP 服务（NativeMcp / ProxyMcp）接入时，按本指南生成测试。目标：测试可复现、可追溯、可留证；外部依赖显式声明，让人类与 AI 都能据声明检查环境或向人求助。

## 按服务类型的两套测试策略

### 本地实现（NativeMcp）— 逐工具真实测试

在 Hub 自己的 JVM 中实现的服务（如 utils、playwright、bocha 的 Native 部分）。**每个 `@Tool` 都应有真实测试用例**，逐个调用并断言结果合理。

- 形态：`@SpringBootTest` 连真实本地端点（MCP client），验证 listTools 覆盖工具集 + 各工具调用成功。
- 示例：`McpUtilsEndpointTest`（utils）、`McpBochaEndpointTest`（bocha Native，远程 API 走 mock）。

### 顶级模块能力层（S1，#53 工具类即源）— plain JUnit 直调

顶级工具模块（bocha/playwright/jina）是纯能力 API，能力测试用 **plain JUnit 直接 new 调用**（不经 Spring/MCP），可注入 mock/fake 或内嵌模拟上游，不触网、不依赖引擎容器（ADR-0016：docker 模块退役，jina 端点配置化）：

- 能力类：`BochaClient`/`JinaReader` 等，构造注入 `RestClient.Builder`/端点 URL + 内嵌 `HttpServer` 上游；`WebSessionRegistry`（playwright 会话租约）经包私有 handle 工厂 seam 注入 mock 句柄测上限/TTL 回收（不触 chromium）。
- 工具类即源：`BochaTools`/`PlaywrightTools`/`JinaTools` 实现 `McpEndpointProvider`，可 `new` 直接调用 `@Tool` 方法测试（mock 能力类验证元数据与路由）。
- 示例：`BochaClientTest`（MockRestServiceServer）、`JinaReaderTest`（file:// 本地解析 + 内嵌上游）、`WebSessionRegistryTest`（会话租约：上限/close/TTL 回收）、`BochaToolsTest`/`JinaToolsTest`/`PlaywrightToolsTest`（源元数据 + 路由，playwright mock 注册表与会话句柄）。

### 转发服务（ProxyMcp）— mock 联通 + 手工具体测试

透明代理外部公有云 MCP / HTTP 的服务（如 github、context7、grep.app、wikidata）。

1. **mock 联通测试（必选）**：用 JDK `HttpServer` 或内嵌 MCP Server 模拟上游，**无外部依赖、无需真实 key**，验证 listTools 透传 + callTool 转发链路走通即可（含 isError 透传、子集过滤、认证注入、优雅降级）。
   - 示例：`McpProxySingleEndpointTest`（内嵌上游 MCP）、`McpBochaEndpointTest`（HttpServer mock 博查 API）、`McpProxyDegradationTest`（上游不可达 → 源降级）。
2. **手工具体测试（可选但推荐）**：依赖真实外部 web + token 的具体调用，写成 **`main` 函数放入对应服务的单元测试类内**；JUnit 不执行 `main`，不会被误调用；用户或 AI 开发时手工运行，验证时使用。

## main 冒烟函数约定

- **位置**：对应服务的单元测试类内（与 mock 测试同处），或独立展示模板（见 `BochaRealApiSmoke`）。
- **凭据**：经 `SmokeCredentials.get(...)` 读取（**env 优先，application-local.yml 兜底**，与 Spring 运行时占位符 `${KEY:}` 一致；如 `BOCHA_API_KEY`/`GITHUB_AUTH_HEADER`），未设置时打印提示并退出。
- **输出**：步骤化标准输出（`[1/N] ...`），每步一行，异常打印失败步；便于贴入 issue 作为验收证据。
- **模板**：`BochaRealApiSmoke`（位于 `src/test/java/io/xyz/xyz_mcp_hub/BochaRealApiSmoke.java`）。

## 外部依赖声明（必选）

每个测试类的顶部 Javadoc 声明其外部依赖，供人类与 AI 理解如何配置：

| 标记 | 含义 | AI 应做 |
|---|---|---|
| `@requires-web` | 需真实外部网络 | 探活外部域名可达性 |
| `@requires-token 环境变量名` | 需指定环境变量（token） | 检查 `${环境变量名:-unset}` 是否存在 |
| `@requires-service 服务名` | 需先启动部署的服务（未来需求） | 探活服务端口 |
| `@requires-docker` | 需本机 docker daemon 可用（引擎容器冒烟，ADR-0016 compose 部署） | 检查 `docker info` 通过；jina 引擎需先 `docker compose up -d`（未就绪时冒烟提示） |

> **jina 引擎冒烟内存要求（#61）**：jina reader 镜像硬编码 `puppeteer.launch(timeout:10s)`，若 Docker Desktop 的 WSL2 内存不足（`.wslconfig` 里 `memory=1GB` 时可用仅 ~512MB），Chrome 启动被拖到 8-13s 撞超时崩溃，引擎不可用。本机跑 jina 冒烟需 WSL2 ≥4GB 内存（`.wslconfig` 设 `memory=4GB`，按需分配不占宿主；改后 `wsl --shutdown` + 重启 Docker Desktop 生效）。

示例：

```java
/**
 * Bocha 端点冒烟（手工运行，非自动测试）。
 * @requires-web 需真实外部网络（api.bochaai.com）
 * @requires-token BOCHA_API_KEY 从环境变量读取；未设置则退出
 */
```

## AI 执行契约

AI 遇到含 `@requires-*` 声明的测试类时：

1. **检查环境**：`@requires-token` → 检查环境变量是否存在；`@requires-service` → 探活服务端口；`@requires-web` → 确认有网络；`@requires-docker` → 确认 `docker info` 通过。
2. **缺失 → 问人**：向用户索要凭据/配置，或提示写入 `application-local.yml` / 环境变量；不得擅自跳过。
3. **就绪 → 执行**：手工运行 main 冒烟，比对结果合理（非空、无失败标记）即通过。

## 验收门槛

- **手工 main 冒烟至少真实运行一次**，否则议题不许关闭；运行结果（stdout）作为**评论加载到 issue**。
- **普通单元测试/集成测试只需通过**：由 Maven 生命周期强制（`./mvnw test` 不通过无法打包），无需单独贴 issue。

## 运行命令

```bash
# 全部测试（跳过 Vaadin 前端构建）
./mvnw test -Dvaadin.skip=true

# 运行某测试类内的 main 冒烟（须加 -pl hub：根聚合 pom 不含测试类，缺省会 ClassNotFoundException）
./mvnw exec:java -pl hub -Dexec.mainClass=<含 main 的测试类全名> -Dexec.classpathScope=test -Dvaadin.skip=true
```
