# sidecar: markitdown

markitdown-mcp 容器化封装骨架（ADR-0012 的 `sidecars/{markitdown}` 目录）。

- **当前**：骨架占位（`pom.xml` 仅声明坐标，无构建逻辑），随根聚合 pom 参与 reactor 构建但为空操作。
- **目标**：Dockerfile + Maven 构建钩子，`mvn install` 用 `docker buildx` 构建并安装到本地 docker。
- **实现议题**：#31（sidecar 镜像构建 / manifests 生成）。

本议题（#29）只搭骨架，不实现镜像构建。
