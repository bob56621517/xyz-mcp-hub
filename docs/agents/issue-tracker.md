# Issue 跟踪器:GitHub

本仓库的 issues 与 PRD 以 GitHub issues 形式存在。所有操作使用 `gh` CLI。

## 议题命名格式

- 格式:`<type>: <简述>`
- 类型前缀:`feat`(新功能)、`bug`(缺陷修复)、`refactor`(重构)、`docs`(文档)、`chore`(杂项/构建)、`test`(测试)
- 简述:简体中文,一句话说清该议题交付的行为,控制在 50 字以内
- 示例:`feat: 支持按需注册工具`、`bug: 修复 mcp 端点超时`、`refactor: 重构 Space 领域逻辑`
- 适用范围:此格式自里程碑 1 之后生效;历史议题 #1 保留原名不修改。

## 约定

- **创建 issue**:`gh issue create --title "..." --body "..."`。多行正文用 heredoc。
- **读取 issue**:`gh issue view <number> --comments`,用 `jq` 过滤评论,并获取标签。
- **列出 issues**:`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`,配合适当的 `--label` 和 `--state` 过滤。
- **评论 issue**:`gh issue comment <number> --body "..."`
- **应用 / 移除标签**:`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**:`gh issue close <number> --comment "..."`

仓库从 `git remote -v` 推断——在克隆内运行时 `gh` 会自动完成。

## Pull request 作为 triage 入口

**PR 作为请求入口:否。** _(如需把外部 PR 视为功能请求,改为 `yes`;`/triage` 会读取此标志。)_

设为 `yes` 时,PR 与 issues 走相同的标签和状态,使用对应的 `gh pr` 命令:

- **读取 PR**:`gh pr view <number> --comments`,diff 用 `gh pr diff <number>`。
- **列出待 triage 的外部 PR**:`gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`,只保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的(去掉 `OWNER`/`MEMBER`/`COLLABORATOR`)。
- **评论 / 加标签 / 关闭**:`gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 的 issues 和 PR 共享同一编号空间,所以裸 `#42` 可能是其中之一——用 `gh pr view 42` 判断,回退到 `gh issue view 42`。

## 当技能说"发布到 issue 跟踪器"

创建一个 GitHub issue。

## 当技能说"获取相关工单"

运行 `gh issue view <number> --comments`。

## 寻路操作

由 `/wayfinder` 使用。**map** 是一个单独的 issue,**子工单**作为子 issue。

- **Map**:一个标有 `wayfinder:map` 标签的 issue,承载 Notes / Decisions-so-far / Fog 正文。`gh issue create --label wayfinder:map`。
- **子工单**:作为 GitHub 子 issue 关联到 map(`gh api` 操作子 issues 端点)。未启用子 issue 时,把子工单加进 map 正文的任务列表,并在子工单正文顶部写 `Part of #<map>`。标签:`wayfinder:<type>`(`research`/`prototype`/`grilling`/`task`)。认领后,工单指派给负责的开发者。
- **阻塞**:使用 GitHub **原生 issue 依赖**——规范的、UI 可见的表示。用 `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>` 添加边,其中 `<blocker-db-id>` 是阻塞者的数字 **database id**(用 `gh api repos/<owner>/<repo>/issues/<n> --jq .id`,_不是_ `#number` 或 `node_id`)。GitHub 会通过 `issue_dependencies_summary.blocked_by` 报告(仅开放的阻塞者——实时闸门)。依赖不可用时,回退到子工单正文顶部的 `Blocked by: #<n>, #<n>` 行。当所有阻塞者都关闭时,工单解除阻塞。
- **前沿查询**:列出 map 的开放子工单(`gh issue list --state open`,限定到 map 的子 issue / 任务列表),去掉带有开放阻塞者(`issue_dependencies_summary.blocked_by > 0`,或 `Blocked by` 行中存在开放 issue)或有 assignee 的;按 map 顺序第一个胜出。
- **认领**:`gh issue edit <n> --add-assignee @me`——本会话的第一次写入。
- **解决**:`gh issue comment <n> --body "<answer>"`,然后 `gh issue close <n>`,再把上下文指针(gist + 链接)追加到 map 的 Decisions-so-far。
