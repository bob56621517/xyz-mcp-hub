# ADR-0004: 使用 Spring Modulith 验证模块结构

## 日期

2026-08-05

## 状态

已接受

## 背景

项目使用包名作为模块边界，需要自动化验证来确保：
- 模块间无循环依赖
- 非 API 包不被外部模块引用
- `internal` 嵌套子包不被跨模块访问

## 决策

**引入 Spring Modulith 自动验证模块结构。**

`pom.xml` 中已引入：
- `spring-modulith-starter-core`（compile scope）
- `spring-modulith-starter-test`（test scope）

### 模块定义

```
io.xyz.xyz_mcp_hub.mcp            → 模块 "mcp"（直接子包 = API 包）
io.xyz.xyz_mcp_hub.mcp.internal   → 自动设为 internal（嵌套子包）
io.xyz.xyz_mcp_hub.ui             → 模块 "ui"（直接子包）
```

### 验证测试

```java
@Test
void verifyModuleStructure() {
    ApplicationModules.of(XyzMcpHubApplication.class).verify();
}
```

Spring Modulith 默认规则：
- 直接子包 = 应用模块 = 对外 API 包
- 嵌套子包 = 自动 internal，外部模块不可引用
- 循环引用报错
- 可通过 `@NamedInterface` 显式命名 API 接口

## 后果

- **正面**：自动验证，不需要手写 ArchUnit 规则
- **正面**：生成模块文档和 PlantUML 图（`target/modulith-docs/`）
- **正面**：与包命名约定自然一致
- **负面**：需要遵守 Spring Modulith 的包结构约定（直接子包 = 一级模块）
