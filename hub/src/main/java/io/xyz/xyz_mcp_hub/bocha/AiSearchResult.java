package io.xyz.xyz_mcp_hub.bocha;

import java.util.List;

/**
 * 博查 AI 搜索的完整结果（能力层 VO，#63）：解析自 ai 响应 {@code messages[]}，把 answer/source/
 * follow_up 三类消息按语义归集为结构化字段，交由工具层格式化呈现。
 *
 * @param summary AI 总结答案（{@code type=answer}，Markdown；无则 null）
 * @param pages 网页参考来源（{@code type=source, content_type=webpage}）
 * @param modalCards 模态卡（{@code type=source} 非 webpage，weather_china/baike_pro/…）
 * @param followUpQuestions 追问问题（{@code type=follow_up}，JSON 数组字符串；无则空列表）
 */
public record AiSearchResult(String summary, List<WebPage> pages, List<ModalCard> modalCards,
		List<String> followUpQuestions) {
}
