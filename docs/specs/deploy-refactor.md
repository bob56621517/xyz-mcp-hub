# Spec: 部署重构——compose 化 + docker 模块退役 + 源类型收敛

> **本 spec 取代 `docs/specs/docker-runtime-refactor.md`**（#29~#39 历史总纲，其中 docker 顶级模块管容器生命周期的方向已被 ADR-0016 整体反转）。权威记录：`REFACTOR-HANDOFF.md` + `docs/adr/0016` + 本文件。

## Problem Statement

旧架构（ADR-0009/0012）把容器生命周期管理堆进 JVM：`docker` 顶级模块 = 迷你编排器（`ContainerManager` 首用拉起 + 防重拉 + 健康检查轮询 + TTL 闲置回收线程 + 关闭销毁），底层 `DockerCliOps` 直接 shell docker CLI 子进程。问题：

1. **用 Java 代码实现运维**：compose/systemd 的活，维护压力大、失败模式多（JVM 崩溃容器泄漏、回收竞态、健康检查误判、pull 挂起）。
2. **API 混杂**：`ContainerEndpoint`/`Protocol`（mcp|rest）等 containermcp 集成概念被提升进 docker 模块（#53）。
3. **部署形态混淆**：compose 形态与 hub 自管形态是同一应用两种部署，应用不该拥有任何一种。
4. **markitdown 冗余**：jina reader（自托管 REST）已覆盖网页/PDF/MS Office + **本地文件上传**（multipart `file`，实测通过），markitdown 唯一独特价值（本地二进制→md）被收编。

## 决策摘要（详见 ADR-0016）

1. **部署归 compose，hub 永不进容器**（`file://` 语义 + playwright HostMcp）。compose 只拉起引擎（现仅 jina，暴露 127.0.0.1），hub 宿主 `java -jar`。
2. **docker 模块整体退役**：应用不碰 docker（不 shell CLI、不用 docker-java）。
3. **端点配置化**：容器源端点改配置 URL，profile 分层（dev→127.0.0.1，prod→compose DNS）。
4. **markitdown 退役，jina 收编**：引擎只剩 jina（GHCR pull）；jina = native 型源，只做 read；搜索用 bocha。
5. **源类型三型收敛**：container 溶解，type 按消费协议（native/proxy/host）。
6. **`file://` 坐标系**：= hub 宿主文件系统；jina 本地文件 = hub 读宿主文件 → multipart 上传。
7. **图片不依赖 jina 内部字幕**（默认绑定 OpenRouter、无自定义端点配置口）；jina 用 `x-retain-images: all` 留图 URL，图片理解归 hub 独立 vision 工具（后续）。

## 代码变更

### 删除

**docker 顶级模块**（`io.xyz.xyz_mcp_hub.docker` 全部）：
- `DockerOps.java` / `DockerCliOps.java`（`docker.internal`）/ `ContainerManager.java` / `ContainerSpec.java` / `ContainerSpecReader.java` / `ContainerHandle.java` / `ContainerEndpoint.java` / `PortProbe.java` / `DockerProperties.java` / `Protocol.java`

**containermcp 包**（`io.xyz.xyz_mcp_hub.mcp.internal.containermcp`）：
- `ContainerMcp.java` / `ContainerMcpClient.java` / `MarkitdownContainerMcp.java` / `MarkitdownTools.java`
- `JinaTools.java` **迁出**至 jina 顶级模块（native 源，见下）

**测试**：
- `docker/ContainerManagerTest`、`docker/ContainerSpecReaderTest`、`docker/DockerContainerSmoke`
- `containermcp/MarkitdownContainerMcpTest`、`containermcp/MarkitdownContainerMcpSmoke`、`containermcp/JinaSmoke`、`MarkitdownContainerMcpEndpointTest`
- `jina/JinaReaderTest` 改造（去容器依赖，注入 fake 端点）

**构建产物与模块**：
- `sidecars/markitdown/`（pom + Dockerfile）整体删除
- `manifests/mcp-images.yaml` + `mcp-images.yaml.tpl` 删除
- 根 `pom.xml`：`<modules>` 只剩 `hub`；删 antrun（manifest 生成）插件；删 `mcp.*` properties

### 新增 / 改造

**jina 顶级模块（native 源，端点配置化）**：
- `JinaConfig`：从配置构建 `JinaReader`（无 `ContainerManager`/`ContainerSpecReader` 依赖），`jina.url` 注入
- `JinaReader`：构造入参改为端点 URL；`readUrl(url)` → POST `{"url":...}`；`readLocalFile(fileUri)` → 读宿主文件 → **multipart `file` 上传** → md；`isAvailable()` = 端点已配置（+ 可选探活）
- `JinaRestClient`：新增 **multipart 上传**能力（`HttpClient` 拼 `multipart/form-data`，`file` 字段 + 文件名）；保留连接层重试（覆盖引擎启动窗口）
- `JinaTools`（迁入 jina 包）：native 型源，`reader` 工具支持 `http(s)://`（SSRF 预检后代抓）与 `file://`（宿主文件上传）；降级返回友好文本

**配置（`application.yaml` / `application-*.yml`）**：
```yaml
jina:
  url: ${JINA_URL:http://127.0.0.1:18081}   # 容器/上游端点；dev/prod 走 profile
```
- 删除 `docker:` 配置段（enabled/manifest-path/ttl/scan-interval/start-timeout/pull-timeout/command）

**源类型收敛**：
- `SourceType` 枚举：删除 `CONTAINER`，收敛 `NATIVE` / `PROXY`（+ `HOST` 若有）
- 目录 catalog：`type` 字段不再出现 `container`；jina 报 `native`，markitdown 不再注册
- `McpEndpointProvider.getProtocol()` 语义核对（native 源无 protocol 字段可退役）

**compose**：
- 新增 `compose.yml`：`jina` 服务（`ghcr.io/jina-ai/reader:latest`，`127.0.0.1:18081:8081`，healthcheck + restart，隔离网络）。dev/prod 加固差异走 `--profile` 或两个文件
- hub 不在 compose（宿主 `java -jar`）

**保留不动**：`mcp.proxies` 通用转发器、playwright（host）、bocha、单端点 + URL 工具视图 + 目录 API（ADR-0011）、`SsrUrlGuard`（jina http(s) 转发前预检）。

## 测试策略

好测试只验证外部可观察行为。重构后：

1. **jina 能力（native 源）单测**：`JinaRestClient`/`JinaReader` 注入本地 `HttpServer` 模拟上游，断言 POST JSON 与 multipart 上传（含边界：非 2xx 抛错、连接层重试、multipart 文件名/字段）；`JinaTools` 注入 fake reader，断言 http(s)/file:// 路由、SSRF 拦截、降级文本。**不启 docker、无网络**。
2. **目录/注册**：catalog 不再有 `container` 型；jina `type=native`、`enabled` 由端点配置门控；markitdown 源不再出现。
3. **真实链路冒烟（手工，`@requires-docker`）**：`docker compose up` 起 jina → hub 连 `127.0.0.1:18081` → `jina_reader` http(s) 代抓 + 本地文件上传，结果贴 issue。按 `docs/testing/mcp-service-test-guide.md`。

验收门槛：`./mvnw test -Dvaadin.skip=true` 全绿；`docker`/`containermcp` 相关类与测试删除后无残留引用；catalog 无 `container` 型。

## Out of Scope

- **图片 vision 工具**：jina 只留 `x-retain-images: all` 图 URL；"本地图片文件/网页图 → 描述"的独立 vision 源（直调用户端点）为后续议题。
- **markitdown 的 EPUB 等边缘格式**：jina 不显式支持，接受缺失。
- 分发（hub 宿主运行，无镜像）、远端/云部署、ProxyMcp 工具清单 TTL 刷新。
