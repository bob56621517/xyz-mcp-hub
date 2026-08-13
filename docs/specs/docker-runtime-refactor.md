# Spec: docker 运行时化重构（四类 MCP + 单端点收敛）

> **⚠ 注记（2026-08-11）**：本文档为历史 spec（#29~#39 实现总纲）。其中**组合源（`mcp.specs`）章节已退役**——组合源机制整体移除（issue #49，唯一用例 github-readonly 定位反复、价值存疑），目录不再有 `type=composite` / `base` 溯源，见 ADR-0011 修订与 CONTEXT.md。

## Problem Statement

当前实现把引擎重实现堆进 JVM（fetch 门面 + content 引擎：Readability/turndown/pdfbox/playwright 渲染编排/markitdown 子进程），**过度造轮子**，背离薄实现。多端点架构（`/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*`）依赖配置 Space 表达工具组合，改一次组合要改一次 YAML。

用户需要一个**由 docker 作为统一运行时的 MCP Hub**：所有重引擎（网页抓取、文件转换、浏览器自动化）交给成熟第三方 MCP（或容器化），Hub JVM 只做薄编排与安全；工具子集由**连接 URL 参数**在运行时选择，**URL 原生能过滤就不造轮子**。

## Solution

**把项目重构为：docker 运行时化 + 单端点 URL 选工具。**

1. **四类 MCP**（ADR-0009）：ProxyMcp（公有云转发）/ NativeMcp（JVM 内薄实现）/ ContainerMcp（本地容器按需拉起）/ HostMcp（同宿主，例外）。确立**薄实现原则**——能力若已有成熟第三方 MCP（或可容器化）就转发/拉容器，绝不 JVM 重造。`fetch` 门面与 `content` 引擎整体退役，网页/PDF 直接用 jina（ContainerMcp rest），文件格式用 markitdown（ContainerMcp mcp）。
2. **单端点 + URL 参数**（ADR-0011）：`/xyz-hub/mcp`（Streamable HTTP）+ `/xyz-hub/sse`（HTTP+SSE，双传输默认开）。`?includes=[jina,bocha_search]&excludes=[]` 在连接/请求级过滤 `listTools` 返回的工具视图。组合源（`mcp.specs` YAML）发布新源入目录。目录 API `GET /xyz-hub/catalog` 先行。
3. **分发与仓库结构**（ADR-0012）：多模块 Maven（Maven = 最终打包入口）；hub 是标准 Spring Boot 应用（不进 docker，`java -jar` 直启）；`mvn install` 只构建 + 安装 sidecar 镜像到本地 docker；`manifests/mcp-images.yaml` = mvn 生成的运行规范（`image`/`protocol`/`port`）；`docker` 顶级模块管容器生命周期（首用拉起 + 闲置回收）。
4. **SSRF**（ADR-0010）：复用现有 `SsrUrlGuard` 迁至共享 `security` 包；容器代抓前 `check(url)` 静态预检 + 容器网络隔离兜底。

## User Stories

1. 作为 LLM 使用者，我想连接**单个 MCP URL**，用 `?includes=[...]` 只暴露我要的工具，以便节省 Token。
2. 作为 LLM 使用者，我想无参数连接时拿到全量工具，以便默认连接开箱即用（向后兼容）。
3. 作为开发者，我想 `includes`/`excludes` 引用**源名**（`jina`，整源）或**工具名**（`bocha_search`，精确一个），以便灵活组合。
4. 作为开发者，我想 URL 参数与组合源 YAML **同一套下划线平坦名语法**，以便两处心智一致、零映射。
5. 作为开发者，我想 `includes` 引用未知源/工具时**静默忽略 + 日志 warn**，以便 client 引用过期源不崩连接。
6. 作为运维，我想在 `mcp.specs` 定义一个**组合源**（如 `github-readonly` = `{includes:[github], excludes:[...]}`），以便复用常用过滤集。
7. 作为开发者，我想组合源**可嵌套**、发布时**循环检测**，以便由组合源再组合出组合源且不陷入循环。
8. 作为使用者，我想 `GET /xyz-hub/catalog` 列出全部源与工具（含组合源溯源），以便任何客户端可枚举并按需拼 URL。
9. 作为使用者，我想有一个 **web 页 URL 构建器**（勾选源/工具 → 复制 URL），以便不用手写参数。
10. 作为开发者，我想 `ProxyMcp` 在**启动时发现**公有云上游的工具清单，以便上游换工具后重启即生效、不改代码。
11. 作为开发者，我想 `ContainerMcp` 的 `mcp` 型容器（markitdown-mcp/playwright-mcp）工具清单用**静态冒烟数据**，以便工具集与所 pin 镜像版本绑定、确定可测。
12. 作为开发者，我想 `ContainerMcp` 的 `rest` 型容器（jina）工具由**薄包装代码固定**，以便 REST API 有稳定 MCP 工具形状。
13. 作为开发者，我想 `ContainerMcp` **首用拉起 + 闲置回收**容器，以便冷引擎不启动不占内存、热引擎按需就绪。
14. 作为开发者，我想 `ContainerSpec.protocol` 支持 `mcp | rest` 两种协议，以便容器即 MCP server 与容器即 REST 服务都能接入。
15. 作为运维，我想 `ContainerMcp` 启动的容器**绑 127.0.0.1、放隔离网络**，以便限制引擎对宿主内网的可达性。
16. 作为开发者，我想 Hub 在把用户 URL 交给 jina/markitdown 前做 **SSRF 静态预检**（scheme 白名单 + 内网/保留段拦截），以便内网地址被拒并友好提示。
17. 作为使用者，我想同时支持 **Streamable HTTP 与遗留 HTTP+SSE** 两条传输，以便任意 MCP client 都能连。
18. 作为开发者，我想转发来的公有云工具命名为 `{source}_{tool}`（如 `context7_query_docs`），以便跨源全局唯一、可被 `excludes` 精确减。
19. 作为运维，我想 `mvn install` 一条命令**构建并安装 sidecar 镜像到本地 docker**，以便引擎就绪只需一条命令。
20. 作为开发者，我想 hub 保持**标准 Spring Boot 应用**（`java -jar` 直启、不进 docker），以便省去 hub 镜像构建/发布整套负担。
21. 作为运维，我想 `manifests/mcp-images.yaml` 由 mvn 从各 sidecar 模块**生成**（镜像名单一事实源），以便不漂移。
22. 作为开发者，我想 `docker` 顶级模块（容器生命周期管理）**可注入/mock**，以便单测不依赖真实 docker。
23. 作为开发者，我想旧的 `/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*` **干净断掉**，以便不再维护两套端点机制。
24. 作为开发者，我想 `SsrUrlGuard` 迁到共享 `security` 包后**原有测试沿用**，以便安全组件零重写。
25. 作为 LLM 使用者，我想网页/PDF 抓取直接用 **jina**（替代旧 fetch 工具），以便拿到成熟可靠的 Markdown 而不用自研引擎。

## Implementation Decisions

- **四类 MCP 与薄实现原则**（ADR-0009）：NativeMcp 一律薄（包装 HTTP API/SDK 或转发/容器化，不重造引擎）；HostMcp 为例外（同宿主文件/程序/真实浏览器交互，如 playwright 非无头改页面，仍以官方 SDK 调用为主）。`fetch` 门面与 `content` 顶级模块整体退役；`HtmlToMarkdown`/`PdfTextExtractor`/Readability/分块/playwright 渲染编排删除。
- **`ContainerSpec`**：`image`（镜像名）、`protocol`（`mcp` | `rest`）、`port`（容器内监听端口，镜像固定）、`hostPort`（宿主映射端口，一律 5 位数、避开 8080/8081 等常用端口）。`mcp` 型转发容器 MCP 工具；`rest` 型由 JVM 薄包装容器 REST API（如 `jina_reader(uri) → markdown`）。
- **工具清单来源规则**：谁控制变更谁静态——公有云 ProxyMcp 启动时 `listTools` 发现；容器 mcp 静态冒烟数据（镜像 pin 由我们控制）；rest 包装/native/host 代码声明；组合源启动时解析。
- **端点**（ADR-0011）：单 `McpServer` + 每请求/会话按 URL 参数解析工具视图；双传输（`/mcp` Streamable HTTP + `/sse` HTTP+SSE）默认开、共享同一过滤（过滤是应用层，与传输无关）。
- **URL 参数语法**：`includes`/`excludes`（复数），`[a,b]` 方括号列表，项 = 下划线平坦名（源名展开该源全部工具 / 工具名精确一个）；解析先精确匹配工具名、再按源名 `{source}_` 前缀展开；无参 = 全量；未知项静默忽略 + warn。
- **组合源（specs）**：`mcp.specs` YAML（`specName: {includes, excludes}`）→ 发布新源入目录；启动时静态解析、可嵌套、发布时循环检测；与普通源同等被 `includes` 引用。组合端点 Space 的 VO/SPI（`SpaceDefinition`/`SpaceSource`/`SpaceDefinitionSource`）已随旧多端点整体移除（issue #39），组合能力全部由 specs 承担。
- **目录 API**：`GET /xyz-hub/catalog`，每源含 `name`/`type`（native/proxy/container，#50 收敛，host 并入 native 靠 scope）/`protocol`（container 专有）/`scope`/`enabled`（注册/启用分离，#50）/`tools`；组合源与 `base` 溯源已整体移除（#49）；数据三源汇合（代码声明 + 静态冒烟 + 启动发现）；无认证、仅本地可读。
- **仓库结构**（ADR-0012）：根聚合 pom + `hub/` + `sidecars/{markitdown,playwright}` + `manifests/` + `compose.yaml`（可选，仅起 hub）；hub 标准 Spring Boot；`mvn install` 只构建 + 安装 sidecar 镜像（buildx，各 sidecar 模块 pom 触发）；`manifests/mcp-images.yaml` = mvn 生成的运行规范。
- **docker 顶级模块**：容器生命周期管理（首用拉起/健康检查/闲置回收/防重拉/关闭销毁），与 `playwright` 同级；`ContainerMcp` 经它按需起容器（jina 从 GHCR pull，sidecar 从本地镜像）。
- **SSRF**（ADR-0010）：复用 `SsrUrlGuard` 迁至 `security` 包；容器代抓用 `check(url)` 静态预检；完整 DNS 锁定路径（`resolveAndCheck`）保留给未来 hub 直连；容器绑 127.0.0.1 + 隔离网络兜底重定向/DNS rebinding。

## Testing Decisions

好测试只验证外部可观察行为，不锁实现细节。重构后测试落在 **1 主 + 2 次** seams：

1. **主 seam = 单 MCP 端点 `/xyz-hub/mcp` + URL 参数**（`@SpringBootTest` + MCP client 连真实端点）：
   - 工具视图过滤：`includes`/`excludes` 语义（源展开/工具精确/组合/`[a,b]` 列表）、无参=全量、未知项静默忽略返回空集不崩。
   - 组合源：specs 发布的源出现在目录、`includes=spec` 生效、嵌套与循环检测（循环定义被拒）。
   - 双传输：`/mcp` 与 `/sse` 均连通、过滤行为一致。
   - 目录 API：`GET /xyz-hub/catalog` 形状（type/protocol/scope/tools/base 溯源）。
   - SSRF：用户 URL 工具（jina_reader 等）传内网/保留段 URL 被拒并返回友好文本。
   - 先例：`McpSingleEndpointTest` / `McpCatalogEndpointTest` / `McpGithubEndpointTest` / `McpBochaEndpointTest` / `McpUtilsEndpointTest` / `McpOldEndpointsRemovedTest`（旧端点 404 契约）。
2. **次 seam A = `ContainerManager`（docker 模块）可注入/mock**：单测用 fake（返回假 `baseUrl`），验证 ContainerMcp 装配与工具视图，**不启 docker**；真实容器链路（拉起 jina/markitdown → 转发 → 闲置回收）按 `@requires-docker` 手工 main 冒烟（按 `docs/testing/mcp-service-test-guide.md`），运行结果贴 issue。
   - 先例：`MarkitdownServerTest`（生命周期 mock 手法）、`FetchServiceTest`（本地 HttpServer）。
3. **次 seam B = SSRF 守卫**：`SsrUrlGuardTest` 迁至 `security` 包沿用（完整保留）。
4. **sidecar 镜像**：构建期冒烟（`mvn install` 后 `docker run` 手工验证），不进单测；`manifests/mcp-images.yaml` 生成内容由构建校验。

验收门槛：`./mvnw test -Dvaadin.skip=true` 全绿；ContainerMcp/目录/工具视图单测不启 docker、无网络；真实容器冒烟至少运行一次、结果评论加载到 issue。

## Out of Scope

- **web 页 URL 构建器**：目录 API 先行，页面延后（是否 Vaadin 未定）。
- **分发 #2**：hub/sidecar 镜像发布到 Docker Hub 直接使用（暂缓）；跨架构（amd64/arm64）发布留待分发 #2。
- **ProxyMcp 工具清单周期刷新（TTL）**：当前仅启动时发现一次。
- **远端/云部署**：一律不做，只做本地 docker。
- **Vaadin 管理 UI 去留**：延后决策。
- **任务拆解与多 issue**：本 spec 为总纲 PRD，按 #22 先例在后续对话拆 ticket（如 hub 端点重构、docker 模块、sidecar 构建、目录 API 各自成 ticket）。

## Further Notes

- **实现状态**：全部子议题（#29~#39）已合入。旧多端点（`/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*`）与 Space 组合端点已随 #39 整体移除（HTTP 404，无重定向），仅剩单端点 `/xyz-hub/mcp` + `/xyz-hub/sse` + 目录 `/xyz-hub/catalog`。
- 本 spec **取代 #22/#24/#25/#26 方向**（content 引擎与 fetch 门面退役），并取代 ADR-0003（NativeMcp 为主）与 ADR-0008（组合端点 Space 命名空间）。
- 权威记录：`REFACTOR-HANDOFF.md` + `docs/adr/0009~0012` + 本文件。
- **彻底重构、无兼容性保证**：旧端点/旧配置干净断掉，项目当前无生产用户。
- 未决小线程（未来对话处理）：目录 schema 细节字段、web 页技术选型、`mcp.specs` 与 `manifests/mcp-images.yaml` 的 schema 校验、ProxyMcp 刷新 TTL。
