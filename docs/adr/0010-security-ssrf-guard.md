# ADR-0010: SSRF 防护——复用 SsrUrlGuard + 容器网络隔离

## 日期

2026-08-09

## 状态

已接受

## 背景

旧架构中 fetch 门面直接抓取 URL，SSRF 防护由 `SsrUrlGuard`（原位于 `mcp.internal.nativemcp.network.ssrf`）承担，通过「DNS 解析一次并锁定 IP 直连」实现强防护。重构后（ADR-0009）fetch 退役，网页/PDF 由 jina（ContainerMcp rest）代抓，文件格式由 markitdown（ContainerMcp mcp）处理——**抓取发生在容器内，Hub 不再直接建连**。SSRF 防守点因此外移，需要重新定义防护边界。

（注：本 ADR 即 `SsrUrlGuard` Javadoc 中「ADR-0010 决策 6」所指向的决策记录，此前为悬空引用。）

## 决策

**复用现有 `SsrUrlGuard`，不重写；防护边界按「谁抓取」分层。**

1. **组件迁移**：`SsrUrlGuard` 从 fetch 包迁至共享 `security` 包（fetch 已退役，它不再是 fetch 专属）。现有防护能力全部保留：scheme 白名单（仅 http/https）、IPv4/IPv6 全保留段清单（含 IPv4-mapped、6to4/Teredo、前导零/`127.1` 等可疑字面量）、重定向逐跳校验、DNS rebinding 的 IP 锁定直连、可注入解析器便于测试。
2. **容器代抓模式（jina / markitdown）**：Hub 在把用户 URL 交给容器前，调用 `guard.check(url)` 做**静态预检**（scheme + host + IP 字面量拦截）。此时不解析真实域名——解析与抓取都由容器完成，Hub 无法锁定 IP。
3. **未来 hub 直连场景**：完整路径 `resolveAndCheck(url)`（解析一次并锁定 `ResolvedTarget`，用返回 IP 直连、严禁二次解析）保留，供任何「Hub 侧发起抓取」的场景使用。
4. **容器网络隔离兜底（与薄守卫双保险）**：`ContainerMcp` 启动容器时绑定 `127.0.0.1`、放入隔离网络，限制容器对宿主机内网/保留段的可达性。重定向逐跳与 DNS rebinding 在容器代抓模式下由该隔离兜底（Hub 无法逐跳校验容器内的重定向）。

## 后果

- **正面**：安全组件零重写、测试沿用（`SsrUrlGuardTest`）；薄守卫几行成本；容器隔离顺手降低攻击面。
- **负面**：容器代抓模式存在**固有 TOCTOU 窗口**（守卫校验字符串后，容器内可能二次解析到内网）——这是「外包抓取」的固有代价，薄守卫 + 容器隔离是我们接受的防御纵深，不做更重的「IP 锁定直连」。
- **边界**：本防护只覆盖「接受用户 URL 的工具」（jina_reader、markitdown 的 uri 参数）；ProxyMcp 的上游 URL 来自固定配置，不受用户输入控制，不在本防护范围。
