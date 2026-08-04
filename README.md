# xyz-mcp-hub

一个基于 Spring Boot 的 MCP（Model Context Protocol）工具聚合服务。为本地 Agent CLI 提供按空间（Space）分组的、可按需注册的 MCP 工具——解决 MCP 工具全量注册导致的 Token 浪费问题。

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

### Space（空间）

Space 是工具的命名分组单元。用户创建一个 Space，获得唯一 UUID 标识，在此 Space 内声明需要暴露的工具。Agent CLI 通过 `localhost:8080/mcp/{uuid}` 连接特定 Space，只注册该 Space 内的工具——按需使用，节约 Token。

### Tool（工具）

Tool 是 MCP 协议中的最小功能单元。早期阶段，Tool 由开发者在 Java 代码中实现，通过 UI 或 API 将 Tool 分配到一个或多个 Space 中暴露出去。

## 项目结构

```
xyz-mcp-hub/
├── src/main/java/io/xyz/mcphub/
│   ├── tool/          ← 工具定义的领域逻辑
│   ├── space/         ← 空间管理的领域逻辑
│   ├── mcp/           ← MCP 协议端点
│   ├── ui/            ← Vaadin 管理界面
│   └── shared/        ← 基础设施
├── src/main/resources/
│   └── application.yaml
├── src/test/
├── docs/
│   └── adr/           ← 架构决策记录（按需创建）
├── CONTEXT.md          ← 领域词汇表（按需创建）
└── pom.xml
```

## 构建与运行

```bash
# 开发模式
./mvnw spring-boot:run

# 打包 fatjar
./mvnw package
java -jar target/xyz-mcp-hub-*.jar
```

## 第一个里程碑

- [x] Spring Boot 项目骨架
- [ ] 构建可执行的 fatjar（依赖正确）
- [ ] 实现一个 Hello World MCP Tool（返回当前时间）
- [ ] 通过 MCP 客户端验证连通性
