# manifests

构建产物目录（ADR-0012 / CONTEXT.md 的 `manifests/`）。

- `mcp-images.yaml` —— **由 mvn 生成的构建产物**（根聚合 `verify` 阶段从 `mcp-images.yaml.tpl` 替换生成，`@token@` 占位符）。`ContainerMcp` 按需启动容器的运行规范（`image` / `protocol` / `port` / `hostPort`），单一事实源不漂移。
  - `port` = 容器内监听端口（镜像固定，勿改）；`hostPort` = 宿主映射端口（**一律 5 位数**，避开 8080/8081 等常用端口）。
  - `markitdown` 节：本仓库 sidecar 构建的本地镜像（`protocol: mcp`）。
  - `jina` 节：第三方引擎镜像（`protocol: rest`，运行时从 GHCR pull；容器内 8081 = HTTP/1.1，8080 是 h2c 勿用）。
  - 该文件已 `.gitignore`（构建产物，不提交）；`mcp-images.yaml.tpl` 是生成模板，随源码提交。
- **playwright 不在此清单**：playwright 属 HostMcp（本机 JVM 引擎，见 `docs/adr/` 与 CONTEXT.md），不经容器运行，故无镜像节。

实现议题：#31。
