# xyz-mcp-hub

一个基于 Spring Boot 的 MCP（Model Context Protocol）工具聚合服务。对外暴露统一的单端点（`/xyz-hub/mcp`，Streamable HTTP + SSE），内部以「源」（Source）聚合四类 MCP 工具（Native / Proxy / Container / Host），工具子集由连接 URL 参数（`includes`/`excludes`）在运行时选择——解决 MCP 工具全量注册导致的 Token 浪费问题。

## 项目愿景

| 阶段 | 目标 |
|------|------|
| **短期** | 本地运行的 MCP 工具超市，提供可配置的工具空间，为 Agent CLI 按需暴露工具 |
| **长期** | 多租户 SaaS 服务，提供 MCP 服务，实现商业化收费 |

## 技术栈

| 类别 | 选型 | 说明 |
|------|------|------|
| 语言 | Java + JDK 25（LTS） | 追求长期稳定，告别 Python 生态的破坏性更新 |
| 框架 | Spring Boot 4.x | 重度依赖 Spring 生态 |
| UI | Vaadin 25.x | 全栈框架，管理后台与后端同进程运行 |
| 持久层 | Spring Data JPA + SQLite | SQLite 用于开发阶段，JPA 抽象层预留后期切换 PostgreSQL |
| MCP 协议 | Spring AI MCP Server | Spring 官方集成，注解式工具注册 |
| 传输模式 | Streamable HTTP（无状态） | MCP 2.0+ 标准传输，无状态设计，按需支持有状态 |
| 构建 | Maven + spring-boot-maven-plugin | 单一 fatjar 部署，零外部依赖 |
| 安全 | 初版无安全校验 | 后续版本按需引入认证机制 |

## 核心概念

### 源（Source）

源是目录里一个可被 `includes`/`excludes` 引用的工具组（一个 `McpEndpointProvider` 实例，native / proxy / container / host 四类；组合源已移除，#49）。Agent CLI 连接单端点 `/xyz-hub/mcp?includes=[bocha*]`（工具名通配，源名匹配已退役，#51），只暴露所选工具视图——按需使用，节约 Token。

### Tool（工具）

Tool 是 MCP 协议中的最小功能单元，工具名统一加 `{source}_` 前缀保证跨源全局唯一。连接 URL 的 `includes`/`excludes` 参数（工具名下划线平坦名，支持 `*` 通配如 `bocha*`；源名匹配已退役，#51）在运行时选择工具视图；无 `includes` = 全量、显式 `includes=[]` = 空集、无 `excludes` = 不减。

## 项目结构

```
xyz-mcp-hub/
├── pom.xml          ← 根聚合（多模块 Maven，ADR-0012）
├── hub/             ← 核心 JVM 模块：标准 Spring Boot 应用（java -jar 直启，不进 docker）
│   └── src/
├── sidecars/        ← 容器化 sidecar（markitdown 容器镜像；playwright 属 HostMcp 本机引擎，不在此）
│   └── markitdown/  （Dockerfile + pom，mvn install 构建并装入本地 docker）
├── manifests/       ← mvn 生成的运行规范（mcp-images.yaml，见 #31）
├── docs/
│   └── adr/           ← 架构决策记录（按需创建）
└── CONTEXT.md          ← 领域词汇表（按需创建）
```

## 构建与运行

```bash
# 根目录测试（聚合 hub + sidecars，约定跳过 Vaadin 前端构建）
./mvnw test -Dvaadin.skip=true

# 打包 hub fatjar
./mvnw -pl hub package -Dvaadin.skip=true
java -jar hub/target/hub-*.jar

# 开发模式（在 hub 模块）
./mvnw -pl hub spring-boot:run -Dvaadin.skip=true
```

## Playwright 浏览器自动化源（HostMcp）

playwright 以 **HostMcp 源**（ADR-0009 例外定位：同宿主真实浏览器交互）注册于单端点
`/xyz-hub/mcp`，连接方用 `?includes=[playwright*]` 暴露浏览器自动化工具集（导航、可访问性快照、
点击/输入、截图、网络与控制台监听等，按官方 `@playwright/mcp` 工具集实现），无外部 API key。

前置：首次使用前安装 chromium 二进制：

```bash
./mvnw -pl hub exec:java -Dvaadin.skip=true -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

浏览器会话懒启动，首次工具调用时拉起无头 chromium；无头与否可通过 `playwright.headless` 属性配置（默认 true）。
工具名统一加 `playwright_` 前缀（如 `playwright_web_session`），跨源全局唯一。

## 第一个里程碑

- [x] Spring Boot 项目骨架
- [ ] 构建可执行的 fatjar（依赖正确）
- [ ] 实现一个 Hello World MCP Tool（返回当前时间）
- [ ] 通过 MCP 客户端验证连通性
