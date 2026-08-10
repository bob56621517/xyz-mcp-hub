# sidecar: markitdown

markitdown-mcp 容器化封装（ADR-0012 的 `sidecars/{markitdown}` 目录）。

- **形态**：`ContainerMcp` 的 `protocol=mcp` 型容器。镜像内以 **streamable HTTP** 暴露 MCP 端点（`/mcp`，端口 3001），Hub 按需拉起容器后转发其 `convert_to_markdown` 工具。
- **构建**：根目录 `mvn install` 时，本模块在 `package` 阶段用 `docker buildx build --load` 构建并装入本地 docker（当前仅本地架构 amd64，跨架构留待分发 #2）。
- **镜像名**：`${mcp.markitdown.image}` —— **单一事实源在根聚合 pom 的 `<properties>`**，同时被 `manifests/mcp-images.yaml` 的生成逻辑引用，不漂移。
- **运行依赖**：ffmpeg / exiftool（markitdown 转换器需要，参考官方 markitdown-mcp Dockerfile）。
- **实现议题**：#31。
