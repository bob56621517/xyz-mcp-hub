# ADR-0006: JPMS 暂缓 — 上游 MCP SDK 非法模块名

## 日期

2026-08-05

## 状态

已接受（暂缓，跟踪 issue #3）

## 背景

ADR-0002 决策使用 JPMS `module-info.java` 实现编译期模块隔离并支持 jlink。Issue #2 的架构工作落地时，发现该决策被一个上游依赖缺陷阻塞。

## 阻塞详情

依赖 `io.modelcontextprotocol:sdk:mcp-json-jackson3:2.0.0` 的 manifest 声明：

```
Automatic-Module-Name: io.modelcontextprotocol.sdk.mcp-json-jackson3
```

模块名含连字符，不是合法 Java 模块名。实测后果：

- `requires io.modelcontextprotocol.sdk.mcp-json-jackson3;` 语法错误
- javac 无法为该 jar 确定模块名，放置于 `--module-path` 即编译失败
- 该 jar 是运行时 `McpJsonDefaults` 的 ServiceLoader 提供者，无法排除

因此整个依赖树只要走模块路径，任何 `module-info.java` 都无法编译。

## 决策

**暂缓 JPMS module-info.java 与 jlink，改用 Spring Modulith 承担模块边界验证。**

- 模块边界由 `ApplicationModules.of(XyzMcpHubApplication.class).verify()` 自动验证（ADR-0004，已通过）
- 包边界约定（API 包 / internal 嵌套子包）继续生效，由 Modulith 测试强制执行
- 解锁条件与后续步骤记录在 GitHub issue #3

## 后果

- **正面**：不引入构建期 manifest 补丁 hack，避免依赖升级反复破坏
- **正面**：模块边界验证职责完整（Spring Modulith）
- **负面**：无法在编译期由 JVM 强制执行包隔离；无法用 jlink 生成裁剪 JRE（均待 issue #3 解锁）
- **负面**：`native` 为 Java 保留字，规范中的 `mcp.internal.native.*` 落地为 `mcp.internal.nativemcp.*`
