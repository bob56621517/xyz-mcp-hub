# ADR-0015: bocha 搜索重构为单 search 工具（type 路由，能力层 + AI 习惯层）

## 日期

2026-08-12

## 状态

已接受

## 背景

bocha 源原暴露 `web_search` / `ai_search` 两个工具，**原样透传官网 API 参数**（query/count/freshness），描述中性。实测（#63）发现：模型默认走字面匹配的 `web_search`、`ai_search` 被边缘化（除非用户点名），配合默认 count=5 时首次搜索命中率低（T1 返回大量 SEO 污染内容）。根因不是引擎能力，而是**参数默认值 + 工具定位**——官网 API 是给开发者设计的（count/freshness 需自行权衡），原样包装没有消化成「模型使用习惯」。

同期抓取官网飞书文档（#63 §8）确认若干未暴露能力：`include`/`exclude`（指定/排除网站范围，AI Search 仅 include）、`answer`（AI 总结开关，默认 true）、`summary`（长摘要开关）、`modelCard`（模态卡结构化数据，12 种垂域）、追问问题（follow-up）。

## 决策

bocha 重构为**两层**：

1. **能力层**（`BochaClient`，忠于官网）：补参数透传（web 加 `summary`/`include`/`exclude`，ai 加 `include`/`answer`），保持返回格式化文本；ai 响应解析**追问问题**与**模态卡**（`modelCard` 结构化 JSON 直接返回，不转自然语言——最终由 LLM 消费，JSON 紧凑保真）。
2. **工具层**（`BochaTools`，AI 习惯层）：原两工具**合成为一个 `search` 工具**（逻辑名 `search`，经源注册表暴露为 `bocha_search`），参数 `type / query / count / freshness / include / exclude`。

核心行为：

- **`type` 默认 `"ai"`**：路由到 AI Search（`answer` 恒 true），返回总结答案 + 追问问题 + 参考来源 + 模态卡；不决策时走最优解（一次答对，修复 #63 首次命中）。
- **`type="web"`**：路由到 Web Search（`summary` 恒 true 长摘要），返回网页列表；深度调研/多角度/枚举/指定网站用。
- **`count`**：语义为**返回条数上限**，直接透传，默认 **20**（#63 §4.2 饱和点附近；含 `include`/`exclude` 过滤时对返回条数的保证）。
- **`include`/`exclude`：API 有则支持、没有则忽略**——不造本地过滤。web 全透传；ai 仅 `include` 透传、`exclude` 忽略。
- **`summary` 不暴露**，内部按 `type` 策略化（web=true / ai=false）；`answer` 不暴露（ai 恒 true）；`stream` 不暴露（非流式足够）。
- **`base-url` 由配置层决定**（`bocha.base-url`，默认 `api.bochaai.com`；官方文档写 `api.bocha.cn`，两者实测均可达，切换由配置完成不写死）。

**描述文案**（引导模型）：开头「博查联网搜索，**用户主动加入的联网工具**」（#63 §4.5 最强信号：用户意图 + 操作指令）；写明 `type=ai` 默认行为（总结/追问/模态卡）与 `type="web"` 分工（深度/多角度/指定网站）；末尾提示「返回的网页来源在回答末尾将 URL 渲染为超链接附上」。

## 理由与权衡

- **合成单工具 vs 分开两工具**：分开=分工写在工具名里，但现状证明「模型默认走 web」；合成=把「默认走 ai」内置为工具默认行为，一举修复首次命中。代价是深度采集靠描述引导 `type="web"`（模型可能不记得），通过描述里的决策规则缓解。
- **`type` 参数 vs `answer` 布尔**：`type="web"/"ai"` 语义直白（端点名），`answer=true/false` 抽象易歧义；且 type 可扩展。
- **能力层返回文本 vs 结构化**：为简化选文本（不引入本地过滤就无需结构化）。模态卡/追问问题在能力层解析后以 JSON 附加，工具层不加工。
- **include/exclude API 优先 vs 本地过滤**：能 API 不造轮子（Web 全支持、AI 支持 include）；AI 的 exclude 直接忽略而非本地兜底——简化，接受「AI 无法排除网站」这一缺口。
- **`count` 默认 20 而非按 type 分（ai=15/web=20）**：统一实现更简，20 对两者均够。

## 不做什么（Out of Scope）

- 本地过滤 / 结构化返回（见权衡）；`stream` 流式；`exclude` 的 AI 本地兜底；`summary` 暴露给模型；域名写死（配置层决定）。
