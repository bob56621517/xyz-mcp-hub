# ADR-0015: bocha 搜索重构为单 search 工具（两层架构 + base-url 配置化）

## 日期

2026-08-13

## 状态

已接受

## 背景

bocha 源原来暴露 `web_search` / `ai_search` 两个工具，参数与描述原样透传官网 API，没有消化成模型使用习惯。实测模型默认走字面匹配的 `web_search`、`ai_search` 被边缘化，首次搜索命中率低（默认 count 下返回大量 SEO 污染内容）。官网已支持但未暴露的能力——`include`/`exclude` 网站范围、模态卡（垂域结构化数据）、追问问题、`answer` 总结开关——白白浪费。根因是官网 API 为开发者设计（`count`/`freshness` 需自行权衡），原样包装没把「模型该怎么做」消化进工具。

另：base-url 此前写死在 `BochaConfig` 默认值，官方飞书文档接口域名（`api.bocha.cn`）与现状在用的 `api.bochaai.com` 不一致，切换域名需改代码。

## 决策

把 bocha 重构为**两层**，并把 base-url 抽象为配置：

### 两层架构

- **能力层**（`bocha` 顶级模块的 `BochaClient`，#53 顶级模块）：忠于官网，补参数透传（`include`/`exclude`/`answer`/`summary`），解析**模态卡**（`modelCard`）与**追问问题**（`type=follow_up`），保持格式化文本返回。零 MCP/Spring AI 依赖。
- **工具层**（`BochaTools` 工具类即源，#53）：原两工具**合成为一个 `search` 工具**，暴露名 `bocha_search`（逻辑名 `search`，源注册表加 `{source}_` 前缀）。工具签名：`type / query / count / freshness / include / exclude`。

### type 路由

- `type` 默认 `"ai"` → AI Search（内部 `answer=true`，返回总结答案 + 追问问题 + 参考来源 + 模态卡）。
- `type="web"` → Web Search（内部 `summary=true` 长摘要，返回网页列表）。
- 描述文案引导模型：默认走 AI 语义搜索（一次答对），深度/多角度/枚举/指定网站时显式 `type="web"`。

### 参数消化

- `count`：语义 = **返回条数上限**，默认 **20**（#63 §4.2 饱和点），clamp 1..50，统一按 type 透传（web 亦透传）。
- `include`/`exclude`：**API 有则支持、没有则忽略**，不做本地过滤。web 全透传；ai 仅 `include` 透传、`exclude` 忽略（官网 AI Search 无 `exclude` 参数）。
- `summary`/`answer`/`stream` 不暴露：`summary` 内部按 type 策略化（web=true / ai=false）；ai 内部 `answer=true`；非流式（`stream=false`）足够。
- `freshness`：支持枚举（noLimit/oneDay/oneWeek/oneMonth/oneYear）与日期范围（`YYYY-MM-DD..YYYY-MM-DD`），默认 noLimit。

### 模态卡与追问问题

- 模态卡：ai 响应 `source` 消息 `content_type` 各异（`weather_china`/`baike_pro`/…），content 为 JSON 数组字符串、数组项含 `modelCard`——**结构化 JSON 直接返回**（不转自然语言，最终由 LLM 消费，JSON 紧凑保真）。
- 追问问题：ai 响应 `type=follow_up` 消息 content 为 JSON 数组字符串，呈现为问题列表。

### base-url 配置化

- base-url 由配置键 **`bocha.url`** 决定，默认 **`https://api.bochaai.com`**（现状在用的域名）；官方飞书文档为 `api.bocha.cn`（`/v1/web-search`、`/v1/ai-search` 均实测可达），切换域名只改配置不改代码。
- 冒烟 `BochaRealApiSmoke` 亦读 `BOCHA_URL` env（缺省同默认值），与 Spring 运行时一致。

## 后果

- 好处：模型一次调用即得可用答案（AI 语义搜索）；垂域数据以结构化模态卡返回可直接引用；网站范围 `include`/`exclude` 聚焦可信来源；部署者可配置切换域名。
- 代价：`web_search` / `ai_search` 两个旧工具消失（破坏性变更，连接 URL 中的旧工具名需改为 `bocha_search`）；AI 的 `exclude` 缺口接受（官网无此参数，不做本地兜底）。
- 不做：模态卡强类型解析（12 种卡各建模型，结构化 JSON 文本返回即可）；本地 include/exclude 过滤；`stream` 流式；`summary` 暴露给模型。
