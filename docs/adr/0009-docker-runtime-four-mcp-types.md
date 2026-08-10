# ADR-0009: docker 运行时化与四类 MCP 模型

## 日期

2026-08-09

## 状态

已接受（**取代 ADR-0003 的「NativeMcp 为主」方向**）

## 背景

旧架构（ADR-0003）以 **NativeMcp 为主**：Hub 在自己的 JVM 里重新实现第三方服务，ProxyMcp 仅用于已有官方公有云 MCP 端点的服务。这导致 fetch 门面及其 content 引擎（Readability / turndown / pdfbox / playwright 渲染编排 / markitdown 子进程）在 JVM 内越造越重——**过度造轮子**，与「薄实现」背道而驰。

与此同时，社区已存在成熟的开源实现：jina-reader（网页/PDF → Markdown，Apache-2.0，多架构镜像）、markitdown-mcp（微软官方，文件 → Markdown）、playwright-mcp（官方 SDK）。**凡是已有成熟第三方 MCP（或可容器化）的能力，都不该在 JVM 里重造。**

## 决策

**引入 docker 作为 MCP 的一种实现/传输方式，MCP 类型扩为四类，并确立薄实现原则。**

### 四类 MCP

| 类型 | 形态 | 薄/例外 |
|---|---|---|
| ProxyMcp | 转发公有云 HTTP MCP Server；工具清单**启动时发现**（上游不受控） | 薄 |
| NativeMcp | JVM 内包装 HTTP API（bocha）或官方 SDK；不重造引擎 | 薄 |
| ContainerMcp | 从本地 docker 按需拉起容器（本地无则按清单 pull）再接入；`ContainerSpec.protocol` 分 `mcp`（转发容器 MCP 工具）/ `rest`（JVM 薄包装容器 REST API） | 薄 |
| HostMcp | 必须同宿主（文件、宿主程序如 IM、真实浏览器交互）；**薄实现原则的例外**，可承载真引擎（如 playwright 非无头改页面），仍以官方 SDK 调用为主 | 例外 |

### 薄实现原则

- **NativeMcp 一律薄**：能力若已有成熟第三方 MCP（或可容器化），就转发/拉容器，绝不在 JVM 里重造引擎。bocha（包装 HTTP API）是典型薄实现正例；旧 fetch/content 是反例。
- **HostMcp 是例外**：因为它与宿主本地文件、程序和真实浏览器交互，是必须的；但它仍以官方 SDK 调用为主（用 playwright 官方 SDK = 薄，自己实现浏览器 = 厚）。
- **fetch 整体退役**：网页/PDF 直接用 jina（ContainerMcp rest 型）；文件格式（Office/EPUB 等）走 markitdown（ContainerMcp mcp 型）。`HtmlToMarkdown`/`PdfTextExtractor`/Readability/分块/playwright 渲染编排、`ConvertEngine`/`FormatConverter`（content 顶级模块）全部退役。

### 工具清单来源规则

**谁控制变更谁静态**：

| 源类型 | 工具清单来源 | 理由 |
|---|---|---|
| ProxyMcp（公有云） | 启动时发现（listTools） | 上游不受控，会换工具 |
| ContainerMcp mcp（markitdown） | 静态冒烟数据 | 镜像由我们 pin，工具集与镜像版本绑定；playwright 属 HostMcp 本机引擎，不走容器（见 ADR-0012 修订） |
| ContainerMcp rest（jina）/ NativeMcp / HostMcp | 静态（代码声明） | 工具就是我们的代码 |
| 组合源 | 启动时静态解析 | 见 ADR-0011 |

### SSRF 防护

复用现有 `SsrUrlGuard`（见 ADR-0010）：容器代抓模式下转发前做静态预检，完整 DNS 锁定路径与容器网络隔离兜底。

## 后果

- **正面**：大幅减少自研重实现，符合薄实现原则；四类模型单一、可测试；镜像数量按生态收敛、内存靠按需拉起控制。
- **正面**：docker 成为统一运行时，任意第三方 MCP 生态可接入。
- **负面**：运行时硬依赖 docker daemon；SSRF 防守点外移（守卫只能转发前校验字符串，抓取发生在容器内，见 ADR-0010）；大量已合入工作作废（#24/#25/#26 及 fetch/content 相关实现）。
- **注意**：本重构为彻底重构，**无兼容性保证**，旧端点/旧配置干净断掉。
