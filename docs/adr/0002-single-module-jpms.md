# ADR-0002: 单 Maven 模块 + JPMS 模块化

## 日期

2026-08-05

## 状态

已接受

## 背景

项目需要模块化以保持代码边界清晰，同时最终需要通过 `jlink` 生成精简 JRE。

有两个可选方向：
- **Maven 多模块**：物理隔离依赖，独立版本管理
- **JPMS (module-info.java)**：编译期强约束包导出，支持 jlink 裁剪

## 决策

**单 Maven 模块 + JPMS module-info.java。**

- 项目只有一个 Maven 模块（`xyz-mcp-hub`），方便全部打包
- 用 `module-info.java` 实现编译期模块隔离
- 所有包默认不导出（`exports` 仅对外 SPi 包 `io.xyz.xyz_mcp_hub.mcp`）
- 最终通过 `jlink` 生成包含裁剪 JRE 的发布包

## 边界划分

包命名承担模块边界职责：

```
io.xyz.xyz_mcp_hub.mcp            → 对外 API（McpEndpointProvider、Scope）
io.xyz.xyz_mcp_hub.mcp.internal   → 不对外（所有实现类）
io.xyz.xyz_mcp_hub.ui.internal    → 不对外（Vaadin 管理界面）
```

## 后果

- **正面**：编译期强制约束，非法包访问在编译阶段报错
- **正面**：jlink 可裁剪出最小 JRE
- **正面**：单模块构建，不需要 care Maven reactor 顺序
- **负面**：需处理 Spring/JPMS 兼容性（如需要 `--add-opens` 等 JVM 参数）
