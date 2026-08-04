# 第一个里程碑：Utils 内置端点 + 测试

## 目标

- `mvn package` 成功产出 fatjar
- 暴露内置 MCP 端点 `/mcp/server/utils`
- 提供一个 `currentDateTime` 工具，返回当前时间字符串
- 通过 Spring Boot 集成测试验证 MCP 连通性

## 技术选型回顾

| 项 | 值 |
|----|-----|
| Spring Boot | 4.1.0 |
| JDK | 25 |
| MCP | Spring AI MCP Server (`spring-ai-starter-mcp-server`, version 2.0.0) |
| 数据库 | SQLite（暂不激活，初版无需持久化） |
| 安全 | 无 |

## 实现计划

### Step 1: `application.yaml` 配置

配置 MCP Server 端点路径和能力，声明内置工具。

### Step 2: `UtilsTools.java`

一个 Spring Bean，用 `@Tool` 注解注册 `currentDateTime` 工具。

```java
@Component
public class UtilsTools {

    @Tool(description = "返回当前日期和时间")
    public String currentDateTime() {
        return Instant.now().toString();
    }
}
```

### Step 3: MCP Server 配置类

用 Spring AI MCP Server 的配置方式，将 `UtilsTools` 注册为工具，暴露在 `/mcp/server/utils` 端点。

需要查阅 `spring-ai-starter-mcp-server` 2.0.0 的文档：
- 如何自定义 MCP 端点路径
- 如何声明式注册工具 Bean

### Step 4: 集成测试

pom.xml 添加测试依赖 `spring-ai-starter-mcp-client`（test scope），写 `McpUtilsEndpointTest`：

1. Spring Boot Test 启动应用（随机端口）
2. MCP 客户端连接 `http://localhost:{port}/mcp/server/utils`
3. 调用 `listTools()` 断言包含 `currentDateTime`
4. 调用 `currentDateTime` 断言返回非空时间字符串

### Step 5: 验证 fatjar

```bash
./mvnw clean package -DskipTests
java -jar target/xyz-mcp-hub-*.jar
# 确认服务启动，可以用 MCP Inspector 或 curl 验证端点
```

## 关键决策

- **包名保留 `xyz_mcp_hub`**，暂不改动 start.spring.io 生成的
- **初版不引入数据库**，SQLite 依赖保留但暂不配置数据源
- **不写 UI**，Vaadin 依赖已在 pom.xml 但暂不使用
- **无状态 Streamable HTTP**，Spring AI MCP Server 默认就支持

## 后续里程碑

1. Space 管理（CRUD + 动态工具分配）
2. Vaadin UI
3. 多租户
