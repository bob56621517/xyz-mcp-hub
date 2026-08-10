# ADR-0012: 分发与仓库结构——多模块 Maven、sidecar 镜像、hub 不进 docker

## 日期

2026-08-09

## 状态

已接受

## 背景

项目原为**单一 Maven 模块**（JPMS 被 issue #3 阻塞，模块化一直压在包层面）。新架构（ADR-0009/0011）引入 docker 运行时与多语言薄封装层：markitdown-mcp（Python）等「非 Java 的 MCP 封装」需要以镜像形式交付，仓库不再是纯 Maven 项目（playwright 属 HostMcp 本机 JVM 引擎，不镜像交付）。需要确定：交付形态、构建入口、各产物的归属。

## 决策

**多模块 Maven，Maven = 最终打包入口；hub 是标准 Spring Boot 应用（不进 docker）；`mvn install` 只负责 sidecar 镜像。**

1. **仓库结构**：根聚合 pom + `hub/`（核心 JVM，单一模块）+ `sidecars/{markitdown,…}`（容器化 sidecar，**最终目标 = Dockerfile/镜像**；playwright 属 HostMcp 本机 JVM 引擎，不容器化）+ `manifests/` + `compose.yaml`。
2. **hub 不进 docker（当前版本）**：就是标准 Spring Boot 应用，`java -jar` 直启；`compose.yaml` 只是**可选的快速启动便捷入口**（起 hub 用，不是引擎启动方式——引擎由 JVM 按需拉起）。
3. **`mvn install` 职责**：只构建 + 安装 **sidecar 镜像到本地 docker**（`docker buildx`，由各 sidecar 模块的 pom 触发）；不再管 hub 镜像。
4. **清单 = 构建产物**：镜像名声明在各 sidecar 模块（第三方引擎如 jina 单独一节），根聚合在打包阶段生成 `manifests/mcp-images.yaml`（`ContainerMcp` 按需启动容器的运行规范，含 `image`/`protocol`/`port`）。单一事实源，不漂移。

   > **修订（#31，2026-08-10）**：Maven reactor 中**根聚合 pom 最先构建**（子模块在后），"根聚合收集各 sidecar 模块声明的镜像名"在 reactor 内不可行。实际实现：镜像名单一事实源**集中在根聚合 pom 的 `<properties>`**（`mcp.<name>.<field>`），各 sidecar 模块 pom 引用 `${mcp.<name>.image}` 作为 docker build tag；`manifests/mcp-images.yaml` 由根聚合 `verify` 阶段从模板 `manifests/mcp-images.yaml.tpl` 生成（antrun `@mcp.<name>.<field>@` 占位符替换）。清单含 `port`（容器内端口，镜像固定）与 `hostPort`（宿主映射端口，一律 5 位数、避开 8080/8081 等常用端口）。单一事实源与不漂移目标不变，仅声明位置由各模块上移至根聚合。
5. **运行期**：JVM 用 `docker` 顶级工具模块（与 `playwright` 同级，管容器生命周期：拉起/健康检查/闲置回收）按需拉起**本地**容器；jina 从 GHCR pull，sidecar 从本地镜像。`ContainerMcp` = 首用拉起 + 闲置回收。
6. **当前版本不做**：
   - 分发 #2（把 hub/sidecar 镜像发布到 Docker Hub 直接使用）——暂缓，前提是维护 dockerhub 容器名称清单；
   - hub 镜像的多架构发布（Jib）——hub 非镜像，不需要；
   - 远端/云部署考虑——一律不管，只做本地 docker。

## 后果

- **正面**：交付路径单一清晰（源码安装 → `mvn install` → sidecar 镜像就位 → `java -jar` 起 hub → 引擎按需拉起）；Maven 保持最终入口，多语言 sidecar 各自独立可测。
- **正面**：清单为构建产物，镜像名单一事实源；hub 不容器化省去镜像构建/发布整套负担。
- **负面**：`mvn install` 依赖本机 docker（构建 sidecar 需要）；hub 直启依赖本机 JRE 25 + docker；容器懒启动有冷启动延迟（首用拉起，镜像已预拉则秒级）。
- **注意**：sidecar 镜像当前仅本地架构（amd64）冒烟；跨架构（arm64）留待分发 #2 时用 buildx 多平台构建解决。
