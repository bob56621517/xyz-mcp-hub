# ADR-0008: 配置驱动的组合端点 Space + 使用维度命名空间

## 日期

2026-08-07

## 状态

已接受

## 背景

现有所有端点均为代码注册的 `McpEndpointProvider`（Native: utils/bocha/playwright；Proxy: github-full/github-readonly/context7/grep-app/wikidata），每个端点暴露固定工具集。README 的长期愿景是"按需暴露工具、节约 Token"——用户需要一种**组合端点**：从多个已有端点挑选工具，聚合成一个新端点，Agent 连它只注册挑中的工具。

同时，`/mcp/server/{name}` 的 `server` 语义含混（Hub 本身才是 server），暴露方式的命名需要一并整理。

## 决策

### 1. 两个正交维度

| 维度 | 分类 | 描述 | 现有成员 |
|---|---|---|---|
| 实现维度（工具从哪来） | NativeMcp / ProxyMcp / HostMcp | 工具如何产生 | utils·bocha·playwright / github·context7·grep-app·wikidata / 预留 |
| 使用维度（端点如何定义） | builtin / config / custom | 端点由谁、以什么方式定义 | 代码内置 / 配置组合 / UI 组合（未来） |

**组合端点在实现维度上不属于任何类**——不自实现、不代理上游，只是引用并拼装其他端点的工具。

### 2. 三个 URL 命名空间（使用维度）

| URL | 含义 | 谁控制 |
|---|---|---|
| `/mcp/builtin/{name}` | 随 jar 内置，源码注册 | 改代码 |
| `/mcp/config/{name}` | 来自 yaml，运维可调 | 改配置 |
| `/mcp/custom/{uuid}` | 用户经 UI 运行时创建 | 改 UI |

`/mcp/server/` 一并迁移为 `/mcp/builtin/`。配置与 custom 用**物理分离的 URL 命名空间**，而非全局去重——因为配置文件无法感知数据库里已存在的 name，静态基线 vs 运行时增量的去重不对称，运维无法修改。

### 3. 配置驱动的组合端点 Space

`application.yml` 的 `mcp.spaces` 声明：

```yaml
mcp:
  spaces:
    devops:
      path: /mcp/config/devops
      sources:
        - source: github-readonly      # 两列表空 → 整端点拉入
        - source: utils
          include: [currentDateTime]   # 只拉这一个
        - source: bocha
          exclude: [bocha_ai_search]   # 整端点减这一个
```

- **include/exclude 为精确工具名枚举**（本任务）；通配符模式匹配留待未来，配置形态不变
- 语义：先 include 后 exclude（排除优先）
- 可引用**任何已注册端点（含 Proxy）**——工具层面均为 `ToolCallback` 对象，物化时无差别

### 4. 注册机制

组合端点是普通 `McpEndpointProvider`，走 `HubMcpRegistrar` 总线，工具列表注册时**物化**（把引用端点的工具合并为 ToolCallback 列表）。**动态注册机制只有一套**（项目铁律，见下）。

### 5. 抽象层：SpaceDefinitionSource SPI

```java
interface SpaceDefinitionSource {
    List<SpaceDefinition> load();
}
```

- `SpaceDefinition` 是统一 VO（name、path、sources[] 含 include/exclude），**按可持久化、可被 UI 编辑的形状**设计
- 本任务实现 `YamlSpaceDefinitionSource`（读 `mcp.spaces`）
- 未来 DB 来源只需新增实现，UI 复用同一 VO/接口——**接口是复用点，不是过度设计**（第二个消费者 UI 是既定规划）

### 6. 解析语义

- 引用未启用端点 → 跳过该条引用 + 日志告警（延续 ADR-0005 优雅降级）
- 引用不存在的工具 → fail-fast 启动报错
- 工具名冲突 → 后注册者覆盖 + 告警

## 考虑过的选项

- **端点级聚合**（一个 Space = 一串 URL 转发）——否决：与现有多端点无本质区别，Agent 仍注册全量工具
- **模式匹配 include/exclude**——本任务否决，通配符需运行时求解工具集、弱化 fail-fast；留待未来
- **单一命名空间 + 全局去重**——否决：静态配置无法感知数据库 name，必须物理分离 URL 命名空间
- **不抽象 SPI**——否决：UI/DB 复用同一 VO/接口是既定规划，抽象即复用点

## 后果

- **正面**：兑现 README"按需暴露工具、省 Token"愿景
- **正面**：暴露方式整理为清晰的使用维度命名空间
- **正面**：SPI + VO 为 UI/DB 复用铺路
- **负面**：`/mcp/server/` 迁移 `/mcp/builtin/` 破坏既有连接（本地开发期，成本低）
- **负面**：配置改动需重启生效
- **负面**：通配符匹配暂不支持，源端点工具集变化需手动改配置

## 项目铁律（关联记忆）

- **动态注册机制只有一套**：所有端点（含 Space）复用 `McpEndpointProvider` + `HubMcpRegistrar` 总线
- 例外需求只能在开发前规划阶段调整机制；进入实现阶段严禁修改
- 实现阶段遇机制限制无法推进：创建"优化框架"前置任务，等作者审核，不私自改框架
