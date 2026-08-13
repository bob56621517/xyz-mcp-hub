# XYZ MCP Hub — 上下文与领域模型

## 项目定位

**MCP 聚合门面（MCP Hub）**。对外暴露一个统一的 MCP 入口，内部通过三种方式（Native / Proxy / Host）整合各类 MCP 工具（`container` 型已溶解——容器只是 compose 部署细节，见 ADR-0016）；工具子集由**连接 URL 参数**在运行时选择，解决「全量注册导致的 Token 浪费」问题。

> 架构方向变更见 `REFACTOR-HANDOFF.md`、`docs/adr/0009~0012`（docker 运行时化 + 单端点收敛）与 `docs/adr/0016`（compose 部署 + docker 模块退役）。

## 领域词汇表

### 核心概念

| 术语 | 英文 | 定义 |
|---|---|---|
| MCP Hub | MCP Hub | 本项目的本体。对外暴露统一 MCP 入口，内部聚合并管理各类 MCP 服务 |
| 源 | Source | 目录里一个可被 `includes`/`excludes` 引用的工具组。**两个正交状态**：已注册（声明存在——本地工具类 / 配置 proxy / 端点配置，由代码或配置固定）与 启用（`isEnabled()` 门控：key/token/端点可用才 true）。目录列出所有已注册源并标 `enabled`；未启用源工具为空 |
| 工具类即源 | Tools as source | #53：本地工具类（`BochaTools`/`PlaywrightTools`/`JinaTools`）**本身实现** `McpEndpointProvider`——`@Tool` 方法 + 源元数据（name/scope/type/enabled，ADR-0016 去 protocol）合一，`new` 即可直接调用测试；纯能力在顶级模块（`BochaClient`/`WebSessionRegistry`/`JinaReader`），MCP 层不再有 `XxxMcpProvider` 包装类 |
| 工具视图 | Tool View | 一次 MCP 连接按 URL 参数解析出的工具子集。**工具永远注册在源里**，`listTools` 返回过滤后的视图给 agent |
| 目录 | Catalog | `GET /xyz-hub/catalog`，机器可读的「源 + 工具」清单。每个源带 `name` / `type`（native/proxy，host 并入 native 靠 scope 区分）/ `scope` / `enabled` / `tools`。数据两源汇合：本地工具类声明 / 配置 proxy 启动发现（容器静态冒烟已随 markitdown 退役，ADR-0016） |
| 清单 | Manifest | **已退役（ADR-0016）**。`manifests/mcp-images.yaml` 曾是 ContainerMcp 按需启动容器的运行规范；docker 模块退役后由 compose 承担部署，无此产物 |
| `file://` 坐标系 | file:// | MCP 的 `file://` 由服务端（hub）解释，统一 = **hub 宿主文件系统**（hub 永不进容器，ADR-0016）。jina 本地文件 = hub 读宿主文件 → multipart 上传 → md |

### MCP 实现类型（三类）

| 术语 | 英文 | 定义 |
|---|---|---|
| 原生 MCP | NativeMcp | 在 Hub JVM 内**薄实现**：包装 HTTP API（bocha / **jina**——端点配置化，本地文件走 multipart 上传）或官方 SDK。遵循薄实现原则，不重造引擎 |
| 代理 MCP | ProxyMcp | 透明转发 HTTP MCP Server（公有云或自部署）。仅支持远程 HTTP（Streamable HTTP），不用 stdio 子进程；认证字段经配置注入固定 header；工具清单**启动时发现**（上游不受控） |
| 主机 MCP | HostMcp | 必须部署在 Agent/CLI 同宿主的 MCP（文件、宿主程序如 IM、真实浏览器交互）。**薄实现原则的例外**：可承载真引擎（如 playwright 非无头改页面注入翻译），但仍以官方 SDK 调用为主 |

> **`container` 型已溶解（ADR-0016）**：容器只是 compose 部署细节，不再是源类型。markitdown 退役（能力被 jina 收编）；jina 归 native（配置端点 + 薄 HTTP 包装）。

### 部署范围

| 术语 | 英文 | 定义 |
|---|---|---|
| 主机 MCP | HostMcp | 见上。强调同宿主；与「网络 MCP」正交 |
| 网络 MCP | NetworkMcp | 通过网络可达即可，对部署位置无约束。NativeMcp / ProxyMcp 均可属于此类 |
| 范围 | Scope | 枚举：`HOST` / `NETWORK`。记录在 `McpEndpointProvider` 中 |

### 工具选择语法（URL 与 YAML 一致）

| 术语 | 定义 |
|---|---|
| `includes` / `excludes` | URL 查询参数。语义：`includes` 先选（并集），`excludes` 再减。**无 `includes` ≡ `[*]`（全量）；`includes=[]` = 空集（不引入任何工具，无语法糖）**；无 `excludes` ≡ `[]`（不减） |
| 项 | `includes`/`excludes` 里的元素 = **工具名**（下划线平坦名，如 `bocha_search`）。**源名匹配已退役**——要某源全部工具写 `bocha*` |
| 通配符 | 工具名支持 `*` 通配：裸 `*`（全量）、`bocha*`（前缀）、`*search`（后缀）、`bo*search`（中间）。**不支持 `?`**；`*` 在 URL query 中合法、无需编码 |
| 列表形式 | URL 用 `[a,b]` 方括号列表 |
| 未知项 | 静默忽略 + 日志 warn（`includes=nonexistent` → 空工具集，`listTools` 返回 `[]`，不使连接失败） |

### 架构原则

| 术语 | 定义 |
|---|---|
| 薄实现原则 | NativeMcp 一律薄：能力若已有成熟第三方 MCP 或服务，就转发/配置端点，绝不在 JVM 重造引擎。本次重构的动因是旧 fetch 违背此原则过度造轮子 |
| 工具清单来源 | 谁控制变更谁静态：配置 proxy（不受控）→ 启动时发现；本地工具类/native/host（代码声明）→ 静态（容器 mcp 静态冒烟随 markitdown 退役，ADR-0016） |

### 任务执行（run-tasks 技能词汇）

| 术语 | 英文 | 定义 |
|---|---|---|
| 前沿 | Frontier | 所有阻塞者已关闭、可立即执行的子议题集合。run-tasks 按前沿**严格串行**派发子代理；兄弟任务同时解锁时按 `# NN` 序数取最前者 |
| 交接 | Handoff | 留给下一个代理/人类的路径与上下文：终局时逐条列出未完成任务（状态、问题/分支在哪、下一步） |
| 待合入 | Awaiting merge | 子代理完成但未获授权/值守模式下未合入的状态；分支已推送，留 `待合入` 评论，等明确授权 |
| 可续作 | Resume-ready | 等待决策的任务收到新的人类答复后，可被重启续作的状态（复用已推分支，落后 main 先 rebase） |
| 值守模式 | Supervised | 用户在场：每任务合并/关闭前停下等明确授权；子代理提问当场转述、等答复 |
| 无人值守 | Unattended | 仅在用户明说会走开时启用：按常设授权自动合入，跑不完的做交接，问题只转述不等待 |

### 任务执行（run_task_with_nobody 技能词汇）

| 术语 | 英文 | 定义 |
|---|---|---|
| work 分支 | Work branch | 启动时从 main 建 `work/<父议题>-<NN>` 并 push；所有子代理提交带 `#N:` 前缀落于此；合入 main 由用户执行 |
| 保绿 | Stay-green | work 分支永远绿，每次 push 都是绿的；失败工作只留本地暂存不推送 |
| 跳过 | Skip | 子代理结局的兜底：🚦 需决策或无法推进一律本地暂存 + work 分支回滚 + 继续下一个不依赖它的任务 |
| 暂存分支 | Staging branch | 本地 `wip/issue-N`，保存失败/半成品工作；只存本地不推远端，名 + sha 记入 🚦/🚫 评论 |
| 等决策 | Awaiting decision | `ready-for-human` + `🚦` 评论（问题 + 暂存分支名 + sha）；人类答复后重启该任务，复用暂存分支 |
| 薄 primary | Thin primary | primary 只建 work 分支、算前沿、派一个子代理、收薄回报、判成功/跳过、写交接；执行全在子代理 |

注：`run_task_with_nobody`（ADR-0014）与 `run-tasks`（ADR-0013）并存。前者的无人值守**结构上不碰 main**（合入 main 由用户执行）；后者的无人值守按常设授权**自动合入 main**。

### 未来规划

| 术语 | 英文 | 定义 |
|---|---|---|
| URL 构建器 🔮 | 无 | `GET /xyz-hub/catalog` 之上的 web 页（勾选源/工具 → 生成 URL 复制）。是否用 Vaadin 实现延后决策 |
| 分发 #2 🔮 | 无 | 把 hub 作为可部署产物分发（暂缓；hub 宿主 `java -jar`，无容器镜像，见 ADR-0016） |
| 周期性刷新 🔮 | 无 | 配置 proxy 工具清单的 TTL 周期刷新（当前仅启动时发现一次） |
| 组合源（打回草稿）🔮 | 无 | `mcp.specs` 组合源机制已退役（代码移除，见 ADR-0011 修订）；将来重做时再评估——白名单搜索工具集、URL 快捷参数、github-readonly 定位等（见对应 issue） |

---

## 架构决策

详见 `docs/adr/`：

- `docs/adr/0001-multi-mcp-server-endpoints.md` — 绕过 Spring AI 自动配置，手动多端点（**已被 ADR-0011 取代**：旧多端点代码已移除，issue #39）
- `docs/adr/0002-single-module-jpms.md` — 单 Maven 模块 + JPMS 模块化
- `docs/adr/0003-native-vs-proxy-mcp.md` — **已被 ADR-0009 取代**（NativeMcp 为主 → 四类 MCP + 薄实现）
- `docs/adr/0004-spring-modulith-verification.md` — 使用 Spring Modulith 验证模块结构
- `docs/adr/0005-configuration-strategy.md` — 配置归入 application.yml + 敏感值环境变量分层注入（**修订**：缺配置 → 已注册未启用）
- `docs/adr/0006-jpms-blocked-upstream.md` — JPMS 暂缓：上游 MCP SDK 非法模块名（issue #3）
- `docs/adr/0007-proxy-http-only-config-driven.md` — Proxy 转发：仅远程 HTTP、配置驱动（**修订**：yaml `mcp.proxies` + 通用转发器 + hook）
- `docs/adr/0008-composed-space-config.md` — **已被 ADR-0011 取代**（组合端点 Space → 组合源 + 单端点 URL 参数）
- `docs/adr/0009-docker-runtime-four-mcp-types.md` — docker 运行时化 + 四类 MCP + 薄实现原则（取代 ADR-0003；**被 ADR-0016 修订**：四类→三类，container 型溶解）
- `docs/adr/0010-security-ssrf-guard.md` — SSRF 防护：复用 SsrUrlGuard + 容器网络隔离
- `docs/adr/0011-single-endpoint-url-params-composite-sources.md` — 单端点 + URL 参数选工具 + 目录 API（**修订**：组合源退役打回草稿、URL 通配符语义、目录 enabled/type 收敛）
- `docs/adr/0012-distribution-multimodule-scope.md` — 分发与仓库结构：多模块 Maven、sidecar 镜像、hub 不进 docker（**被 ADR-0016 修订**：sidecar/manifest/docker 模块退役，部署归 compose）
- `docs/adr/0016-compose-deployment-hub-on-host.md` — compose 部署 + hub 永不进容器 + docker 模块退役 + 源类型三型收敛

---

## 包结构

```
xyz-mcp-hub/
├── pom.xml                     ← 根聚合（多模块 Maven，Maven = 最终打包入口）
├── hub/                        ← 核心 JVM（标准 Spring Boot 应用，不进 docker，java -jar 直启）
│   └── src/main/java/io/xyz/xyz_mcp_hub/
│       ├── mcp                                 ← 模块 1 API 包（对外）
│       │   ├── McpEndpointProvider.java        ← SPI 接口（= 源注册；工具类即源，含 getProtocol 容器元数据）
│       │   ├── Scope.java                      ← HOST / NETWORK
│       │   └── package-info.java               ← @NamedInterface("api")
│       ├── mcp.internal                        ← 以下全部不对外
│       │   ├── single                          ← 单端点 McpServer + 源注册表 + URL 参数工具视图 + 目录 API（ADR-0011，#30~#39；组合源已退役）
│       │   ├── proxy                           ← 通用转发器（配置驱动，yaml mcp.proxies）
│       │   └── nativemcp                       ← 工具类即源与 utils 源（BochaTools/PlaywrightTools/JinaTools 实现 McpEndpointProvider；HostMcp 预留）
│       ├── playwright                          ← 顶级工具模块：浏览器引擎 + 会话（WebSessionRegistry）
│       ├── bocha                               ← 顶级工具模块：bocha 搜索 API（纯能力 BochaClient，#53 提升）
│       ├── jina                               ← 顶级工具模块：jina 解析 API（纯能力 JinaReader：配置端点代抓 + 本地文件 multipart 上传，ADR-0016）
│       ├── security                            ← SsrUrlGuard 等共享安全组件
│       └── ui                                  ← 模块 2（Vaadin 管理界面，延后决策）
├── compose.yml                 ← 引擎部署（ADR-0016）：拉起引擎容器（现仅 jina），暴露 127.0.0.1 端口；hub 以宿主 java -jar 运行，不进 compose
├── CONTEXT.md
└── docs/adr/
```

**说明**：`content` 顶级模块已整体退役（旧内容转换引擎，见 ADR-0009）；`fetch` 门面已砍，网页/PDF 直接用 jina。`docker` 顶级模块与 `containermcp` 包整体退役（ADR-0016：部署归 compose、markitdown 被 jina 收编）；`sidecars/`、`manifests/` 不再存在。旧多端点（`/mcp/builtin/*`、`/mcp/server/*`、`/mcp/config/*`）与 Space 组合端点已整体移除（issue #39），仅剩单端点 `/xyz-hub/mcp` + `/xyz-hub/sse` + 目录 `/xyz-hub/catalog`。**部署**：compose 拉起引擎（jina，127.0.0.1 端口），hub 以宿主 `java -jar` 运行（永不进容器，`file://` 语义见 ADR-0016）。`(JPMS module-info.java 仍暂缓，见 issue #3)`

---

## 配置约定

- `application.yml` — 主配置（含 `spring.config.import`、`mcp.proxies` 配置 proxy 源列表、`jina.url` 端点配置等）
- `application-local.yml` — 本地敏感配置（API key/token，`.gitignore` 排除）
- `spring.profiles.active: local`
- **部署（ADR-0016）**：compose 拉起引擎（jina 暴露 127.0.0.1 端口）；hub 宿主 `java -jar`。dev/prod 端点差异走 profile（dev → `127.0.0.1`，prod → compose DNS）

`mcp.proxies`（配置 proxy 源）示例（通用转发器按此建源，#52 消灭逐个 Provider 类；auth-header 留空 → 未启用）：

```yaml
mcp:
  proxies:
    - name: github
      upstream-url: https://api.githubcopilot.com/mcp/
      # 完整认证 header（如 "Authorization: Bearer <token>"，经 GITHUB_AUTH_HEADER 注入）；留空 → 源未启用
      auth-header: "${GITHUB_AUTH_HEADER:}"
      # tools-subset: []   # 可选：固定工具子集
```

`jina`（native 源，端点配置化，ADR-0016）示例：

```yaml
jina:
  url: ${JINA_URL:http://127.0.0.1:18081}   # jina reader 容器/上游端点（dev/prod 走 profile，见 ADR-0016）
```

---

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Java | 25 | 运行时 |
| Spring Boot | 4.1.0 | 应用框架 |
| Spring AI | 2.0.0 | MCP Server/Client SDK |
| Spring Modulith | 2.1.0 | 模块结构验证 |
| Vaadin | 25.2.5 | 管理 UI（延后决策） |
| SQLite | — | 运行时数据存储 |
| Docker | — | 引擎运行时（仅 jina 容器，compose 部署，暴露 127.0.0.1，ADR-0016） |
| JPMS | — | 编译期模块隔离 + jlink 裁剪 JRE（暂缓，上游阻塞，见 issue #3） |
