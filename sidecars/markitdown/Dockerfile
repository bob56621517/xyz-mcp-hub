# markitdown-mcp sidecar —— ContainerMcp protocol=mcp 用（见 ADR-0012 / #31）
#
# 只安装官方 markitdown-mcp 包（PyPI，锁版本保证镜像可复现），不维护第三方源码。
# 以 streamable HTTP 暴露 MCP（/mcp 端点），供 Hub 的 ContainerMcp 按需拉起后转发。
# 运行时依赖参考官方 markitdown-mcp Dockerfile（ffmpeg / exiftool）。
FROM python:3.13-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV EXIFTOOL_PATH=/usr/bin/exiftool
ENV FFMPEG_PATH=/usr/bin/ffmpeg
ENV MARKITDOWN_ENABLE_PLUGINS=True

# markitdown 转换器运行时依赖：音视频（ffmpeg）/ 元数据（exiftool）
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        exiftool \
    && rm -rf /var/lib/apt/lists/*

# 锁版本：镜像即 pin 的依赖集，升级需显式改版本（ADR-0010/0012 的容器静态冒烟前提）
RUN pip install --no-cache-dir markitdown-mcp==0.0.1a4

WORKDIR /workdir

# 非 root 运行（对齐官方 Dockerfile 的 USER nobody:nogroup），配合容器网络隔离（ADR-0010）
USER nobody:nogroup

# 容器内绑 0.0.0.0：对外可达性由 docker 网络隔离 + 宿主绑 127.0.0.1 控制（ADR-0010/0012），
# 不将引擎接口直接暴露到宿主内网。
EXPOSE 3001
ENTRYPOINT ["markitdown-mcp", "--http", "--host", "0.0.0.0", "--port", "3001"]
