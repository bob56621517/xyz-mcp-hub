# 由 mvn 生成（根聚合 verify 阶段）—— 请勿手改。
# 镜像名单一事实源：根聚合 pom <properties> 的 mcp.* 属性（ADR-0012 / #31）。
# @token@ 占位符在生成时被替换。ContainerMcp 按需启动容器的运行规范（#32 消费）。
images:
  markitdown:
    image: @mcp.markitdown.image@
    protocol: @mcp.markitdown.protocol@
    port: @mcp.markitdown.port@
    hostPort: @mcp.markitdown.hostPort@
  jina:
    image: @mcp.jina.image@
    protocol: @mcp.jina.protocol@
    port: @mcp.jina.port@
    hostPort: @mcp.jina.hostPort@
