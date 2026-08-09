# manifests

构建产物目录（ADR-0012 / CONTEXT.md 的 `manifests/`）。

- `mcp-images.yaml` —— Maven 构建生成的运行规范（`image` / `protocol` / `port`），`ContainerMcp` 按需启动容器的单一事实源。
- **当前**：骨架占位。`mcp-images.yaml` 由 #31 实现生成逻辑（镜像名单一声明于各 sidecar 模块，根聚合打包阶段生成）。

本议题（#29）只搭骨架，不实现生成逻辑。
