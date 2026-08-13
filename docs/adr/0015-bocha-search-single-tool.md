# ADR-0015: bocha 搜索重构为单 search 工具（type 路由，能力层 + AI 习惯层）

## 日期

2026-08-13

## 状态

已接受

## 背景

bocha 源原来暴露 `web_search` / `ai_search` 两个工具，参数与描述原样透传官网 API，没有消化成模型使用习惯。实测模型默认走字面匹配的 `web_search`、`ai_search` 被边缘化，首次搜索命中率低（默认 count 下返回大量 SEO 污染内容）。官网已支持但未暴露的能力——`include`/`exclude` 网站范围、模态卡（垂域结构化数据）、追问问题、`answer` 总结开关——白白浪费。根因是官网 API 为开发者设计（`count`/`freshness` 需自行权衡），原样包装没把「模型该怎么做」消化进工具。

另：base-url 此前写死在 `BochaConfig` 默认值，官方飞书文档接口域名（`api.bocha.cn`）与现状在用的 `api.bochaai.com` 不一致，切换域名需改代码。

## 实测依据（2026-08-13，真实 API 调用，补飞书文档缺漏）

飞书文档对 AI Search **返回结构**描述不全、且有误导，以下为真实调用（`api.bochaai.com`，同 query「杭州天气」）确认的行为：

- **AI Search 请求体带 `exclude` 被静默忽略**：code 200 正常返回，但被排除域名（`qq.com|bocha.cn`）的结果**仍返回**。即 AI 端 exclude **不生效也不报错**（区别于不认识参数报 400）。→「exclude 在 AI 端缺口」成立。
- **AI Search 请求体带 `summary:false` 被静默忽略**：code 200，返回的网页 source **每条仍带 `summary` 字段**，与不带 `summary` 完全一致。AI 侧 `summary` 参数无意义（无对应开关）。
- **AI Search 的网页 source（`type=source, content_type=webpage`）字段与 web 的 `webPages.value` 完全一致**：`name/url/snippet/summary/siteName/siteIcon/datePublished/dateLastCrawled` 均有。即「web 有长摘要、ai 没有」是**错误认知**——两者网页来源数据结构相同。
- **AI Search 额外返回**：`type=answer`（大模型总结）、`type=follow_up`（追问问题，JSON 数组字符串）、模态卡 `source`（如 `weather_china`）、`conversation_id`。
- **web 的 `exclude` 真实生效**（qq.com 被排除）；但 `bocha.cn` 未被排除，疑似精确域名匹配而非子域包含，未深入验证。

结论：除 `exclude`（AI 无）与「仅要裸网页列表、不需模型总结」场景外，**AI Search 一次调用覆盖 web 全部能力并额外返回总结/追问/模态卡**，是默认最优解；其 `answer` 总结可作为模型自主汇总的辅助素材（骨架+参考视角），网页来源仍是溯源依据。

## 决策

把 bocha 重构为**两层**，并把 base-url 抽象为配置：

### 两层架构

- **能力层**（`bocha` 顶级模块的 `BochaClient`，#53 顶级模块）：**纯 API 封装**——返回结构化 VO（`List<WebPage>` / `AiSearchResult`）、参数全透传（含 `summary`/`answer`/`count`/`freshness`，`Boolean` 可空、null 交官网默认）、无默认值、失败抛异常。零 MCP/Spring AI 依赖。
- **工具层**（`BochaTools` 工具类即源，#53）：原两工具**合成为一个 `search` 工具**，暴露名 `bocha_search`（逻辑名 `search`，源注册表加 `{source}_` 前缀）。工具签名：`type / query / count / freshness / include / exclude`。消化「怎么用 API」的策略（默认值、真值、VO 格式化、异常转友好文本）。

### type 路由

- `type` 默认 `"ai"` → AI Search（`answer=true`，返回总结答案 + 追问问题 + 参考来源 + 模态卡）。
- `type="web"` → Web Search（`summary=true` 长摘要，返回网页列表）。
- 描述文案引导模型：默认走 AI 语义搜索（一次答对）。实测 web/ai 网页来源字段相同，web 的独特价值是 **`exclude` 排除网站**（AI 端 exclude 被忽略）——需要排除特定网站或只要裸网页列表（不需模型总结）时显式 `type="web"`。

### 参数消化

- `count`：语义 = **返回条数上限**，默认 **20**（#63 §4.2 饱和点），clamp 1..50，统一按 type 透传（web 亦透传）。
- `include`/`exclude`：**API 有则支持、没有则忽略**，不做本地过滤。web 全透传；ai 仅 `include` 透传、`exclude` 不传（实测 AI 接受 exclude 但静默忽略，不传更干净）。
- `summary`/`answer`/`stream` 不暴露：web 内部 `summary=true`（长摘要）；ai 内部 `answer=true`（总结+追问），ai 侧 `summary` 参数实测被忽略、不传；非流式（`stream=false`）足够。
- `freshness`：支持枚举（noLimit/oneDay/oneWeek/oneMonth/oneYear）与日期范围（`YYYY-MM-DD..YYYY-MM-DD`），默认 noLimit。

### 模态卡与追问问题

- 模态卡：ai 响应 `source` 消息 `content_type` 各异（`weather_china`/`baike_pro`/…），content 为 JSON 数组字符串、数组项含 `modelCard`——**结构化 JSON 直接返回**（不转自然语言，最终由 LLM 消费，JSON 紧凑保真）。
- 追问问题：ai 响应 `type=follow_up` 消息 content 为 JSON 数组字符串，呈现为问题列表。

### base-url 配置化

- base-url 由配置键 **`bocha.url`** 决定，默认 **`https://api.bochaai.com`**（现状在用的域名）；官方飞书文档为 `api.bocha.cn`（`/v1/web-search`、`/v1/ai-search` 均实测可达），切换域名只改配置不改代码。
- 冒烟 `BochaRealApiSmoke` 亦读 `BOCHA_URL` env（缺省同默认值），与 Spring 运行时一致。

### 描述文案

开头「**用户主动加入的用博查搜索引擎的联网 mcp 工具**」；写明 `type=ai` 默认行为（总结/追问/模态卡）与 `type="web"` 分工（排除网站/裸网页列表）；末尾提示「返回的网页来源在回答末尾把 URL 渲染为超链接附上」。参数取值/默认/语法细节放在各 `@ToolParam` 描述（经 `SpringAiSchemaModule` 注入 `inputSchema`），工具描述不重复参数细节。

## 理由与权衡

- **合成单工具 vs 分开两工具**：分开=分工写在工具名里，但现状证明「模型默认走 web」；合成=把「默认走 ai」内置为工具默认行为，一举修复首次命中。代价是深度采集靠描述引导 `type="web"`，通过描述里的决策规则缓解。
- **`type` 参数 vs `answer` 布尔**：`type="web"/"ai"` 语义直白（端点名），`answer=true/false` 抽象易歧义；且 type 可扩展。
- **能力层返回 VO vs 文本**：能力层为纯 API 封装返回结构化 VO（`WebPage`/`AiSearchResult`/`ModalCard`），工具层负责 VO → 文本格式化——职责单一（数据 vs 呈现分离），可复用、可测。成本是工具层需做格式化。
- **能力层默认值归属**：`summary`/`answer` 用 `Boolean` 可空（null 交官网默认）、`count`/`freshness` null 不传——默认值策略（count 20、freshness noLimit、summary/answer 真值）全部上移工具层，能力层不代模型决策。
- **include/exclude API 优先 vs 本地过滤**：能 API 不造轮子（Web 全支持、AI 支持 include）；AI 的 exclude 直接忽略而非本地兜底——简化，接受「AI 无法排除网站」这一缺口。
- **`count` 默认 20 而非按 type 分**：统一实现更简，20 对两者均够。

## 后果

- 好处：模型一次调用即得可用答案（AI 语义搜索）；垂域数据以结构化模态卡返回可直接引用；网站范围 `include`/`exclude` 聚焦可信来源；部署者可配置切换域名；能力层纯封装可独立复用测试。
- 代价：`web_search` / `ai_search` 两个旧工具消失（破坏性变更，连接 URL 中的旧工具名需改为 `bocha_search`）；AI 的 `exclude` 缺口接受（官网无此参数，不做本地兜底）。
- 不做：模态卡强类型解析（12 种卡各建模型，结构化 JSON 文本返回即可）；本地 include/exclude 过滤；`stream` 流式；`summary` 暴露给模型。
