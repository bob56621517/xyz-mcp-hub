## 项目约定

生成的文档统一使用简体中文书写,不要生成英文文档。

## Agent 技能

### Issue 跟踪器

Issues 通过 GitHub Issues 跟踪,使用 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

**议题命名格式**:`<type>: <简述>`。类型前缀:`feat`(新功能)、`bug`(缺陷修复)、`refactor`(重构)、`docs`(文档)、`chore`(杂项/构建)、`test`(测试)。简述用简体中文,一句话说清议题交付的行为。示例:`feat: 支持按需注册工具`、`bug: 修复 mcp 端点超时`。

### Triage 标签

五个标准 triage 角色使用默认标签名(`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`)。详见 `docs/agents/triage-labels.md`。

### 领域文档

单上下文布局:根目录一个 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。
