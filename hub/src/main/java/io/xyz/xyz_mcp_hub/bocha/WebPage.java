package io.xyz.xyz_mcp_hub.bocha;

/**
 * 博查网页搜索/参考源的一条网页结果（能力层 VO，#63）：字段对应官网
 * {@code webPages.value[]}（web-search）与 ai-search 网页 source 的 {@code value[]}。
 *
 * @param name 标题
 * @param url 链接
 * @param siteName 站点名
 * @param snippet 短摘要
 * @param summary 长摘要（实测 web/ai 均带；可为空）
 */
public record WebPage(String name, String url, String siteName, String snippet, String summary) {
}
