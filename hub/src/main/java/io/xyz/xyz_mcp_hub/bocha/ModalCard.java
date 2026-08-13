package io.xyz.xyz_mcp_hub.bocha;

/**
 * 博查 AI 搜索的模态卡（垂域结构化数据，能力层 VO，#63）：来自 ai 响应 {@code type=source}、
 * {@code content_type} 非 webpage 的消息（weather_china/baike_pro/…），content 为 JSON 数组字符串、
 * 数组项含 {@code modelCard}。
 *
 * @param contentType 模态卡类型（如 weather_china / baike_pro）
 * @param modelCardJson modelCard 结构化 JSON 紧凑文本（能力层已解析并保留原样，工具层直接呈现）
 */
public record ModalCard(String contentType, String modelCardJson) {
}
