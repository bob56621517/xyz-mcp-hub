# ADR-0016: 部署重构——compose 化 + docker 模块退役 + 源类型收敛

## 日期

2026-08-13

## 状态

已接受（**修订 ADR-0009/0012 中「docker 顶级模块管容器生命周期」的决定**）

## 背景

旧架构（ADR-0009/0012）把 docker 作为 MCP 的一种实现/传输方式：ContainerMcp 经 `docker` 顶级模块按需拉起容器（首用拉起 + 防重拉 + 健康检查 + 闲置回收 + 关闭销毁），JVM 内嵌一个「迷你编排器」（`ContainerManager` + `DockerCliOps` + reclaimer 线程）。暴露三个问题：

1. **容器生命周期是运维问题，不是开发问题**。用 Java 代码实现 compose/systemd 的活：维护压力大、失败模式多（JVM 崩溃容器泄漏、回收竞态、健康检查误判、pull 挂起）。
2. **`DockerCliOps` 直接 shell docker CLI 子进程**（`ProcessBuilder` + 字符串命令 + 解析 stdout），脆弱、字符串化。
3. **API 混杂**：`ContainerEndpoint`（spec→mcpUrl/restUrl）、`Protocol`（mcp|rest）等 containermcp/MCP 集成概念被提升进 docker 模块（#53），把「容器生命周期」和「MCP 集成」搅在一起。
4. **部署形态混淆**：compose 形态（引擎已拉起，hub 只转发）与 hub 自管形态（按需拉起）本质是**同一应用两种部署**——应用不该拥有任何一种。

同时确认了两条能力事实：

- **jina reader（自托管 REST）已覆盖 markitdown 的全部价值**：网页/PDF/MS Office 转换 + **本地文件上传**（multipart `file` 字段，实测通过）。markitdown 的唯一独特价值（本地二进制→md）被 jina 收编，其余（http(s)）与 jina 重叠。
- **jina 公有云 MCP（`mcp.jina.ai/v1`）是独立 SaaS**：自托管镜像不含 MCP server（`jina-ai/reader` 仓库无 MCP 代码）；且无本地文件上传、需 key、SSRF 自防弱，不作主源。jina 内部 VLM 字幕默认绑定 OpenRouter（`importOpenRouterModel`）、自定义端点无配置口（`OVERRIDE_JINA_VLM_URL` 接在注释掉的客户端上）。

## 决策

1. **部署归 compose，hub 永不进容器**：compose 只拉起引擎容器（现仅 jina），暴露 `127.0.0.1` 宿主端口；hub 是标准 Spring Boot 宿主进程（`java -jar`）。理由：MCP 的 `file://` 由服务端（hub）解释，hub 在宿主 → 语义天然正确；playwright（HostMcp 真实浏览器）也要求 hub 在宿主。**不构建 hub 镜像**（无 Dockerfile/多阶段之争）。
2. **docker 模块整体退役**：`io.xyz.xyz_mcp_hub.docker` 全部删除，应用不再碰 docker（不用 docker-java、不 shell CLI）。引擎可达性由 compose 保证，就绪靠调用层重试（现 `ContainerMcpClient`/`JinaRestClient` 已有重试）。「CLI vs SDK」之争由此消解——应用本就不该碰 docker。
3. **端点配置化**：容器源端点从「docker 物化」改为「配置 URL」，按 ADR-0005 profile 分层（dev → `127.0.0.1`，prod → compose DNS/端口）。
4. **markitdown 退役，jina 收编**：引擎只剩 jina（GHCR pull，无自建 sidecar 镜像）；jina = **native 型源**（薄 HTTP 客户端 + 包装 tool），只做 **read**（网页/PDF/Office/本地文件上传）。搜索用 bocha 已覆盖，不接 `s.jina.ai`。
5. **源类型三型收敛**：`container` 型溶解——type 按**消费协议**分（native=包装 HTTP API，proxy=转发 MCP 端点，host=同宿主引擎），不再按物化方式分。jina: container→native；目录 `type` 字段同步收敛。
6. **`file://` 坐标系**：`file://` 统一 = **hub 宿主文件系统**。jina 本地文件 = hub 读宿主文件 → multipart 上传 → md（零容器 FS 依赖、无共享卷、无 staging）。
7. **图片能力不依赖 jina 内部字幕**：jina 用 `x-retain-images: all` 保留图 URL；图片理解由 hub **独立 vision 工具**直调用户端点（薄实现 + 配置化，图片能力完全 hub 侧可控）。

## 后果

- **正面**：删除一整个编排器模块（10 类 + 测试）；无自建镜像；引擎收敛为一个官方容器；`file://` 语义正确；源类型干净（三型按消费协议）。
- **负面**：hub 运行依赖宿主 Java 25 运行时（无容器化部署路径）；compose 需暴露引擎宿主端口（loopback，安全可接受，配合工具层 SSRF 预检 = ADR-0010 双保险）；本地文件转换依赖 jina 上传的体积/格式边界。
- **作废**：`sidecars/markitdown`（pom + Dockerfile）、`manifests/mcp-images.yaml` + tpl、根 pom 的 `mcp.*` 属性与 antrun manifest 生成、docker/containermcp 相关类与测试。
- **保留**：proxy 模块（`mcp.proxies` 通用转发器）、playwright（host）、bocha、单端点 + URL 工具视图 + 目录 API（ADR-0011）、`SsrUrlGuard`（jina http(s) 转发前预检）。
