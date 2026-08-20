# Issue tracker：backlog.md + GitHub Issues 双轨制

本仓库的工作项（issue）采用双轨制：**backlog.md（本地登记）+ GitHub Issues（正式跟踪）**。两个入口都有效，按下面的分工使用。

## 分工

| 场景 | 去处 | 方式 |
|------|------|------|
| 会话中发现的新需求 / 顺带 bug / 非阻塞改进 | **backlog.md** | 按 AGENTS.md「Backlog 纪律」登记卡片（全局编号 + Tag + checkbox + ≤3 行摘要 + 链接；P0-P3），证据写 journal 批次文件，不现场实现 |
| 需要跨会话跟踪 / 讨论 / 指派的正式工作项，或用户明确要求建 issue | **GitHub Issues** | `gh issue create`（命令速查见下） |
| Triage 标签管理 | **GitHub Issues** | 标签只作用于 GitHub Issues，见 [triage-labels.md](triage-labels.md) |

## 双向同步

- 建 GitHub Issue 前先查 `backlog.md` 避免重复（AGENTS.md Backlog 纪律）。
- `backlog.md` 已有条目时：建 issue 后在该条目上附 issue 编号/链接，状态以 GitHub Issue 为准。
- 无对应条目时：直接建 issue，无需回填 `backlog.md`（避免双份维护）。

## GitHub 命令速查（gh CLI）

- **建 issue**：`gh issue create --title "..." --body "..."`（多行正文用 heredoc）
- **读 issue**：`gh issue view <编号> --comments`
- **列 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`（配相应 `--label` / `--state` 过滤）
- **评论**：`gh issue comment <编号> --body "..."`
- **加/移标签**：`gh issue edit <编号> --add-label "..."` / `--remove-label "..."`
- **关闭**：`gh issue close <编号> --comment "..."`

仓库从 `git remote -v` 推断（origin = github.com:LeoNardo-LB/oc-beacon），`gh` 在 clone 内运行会自动识别。注意：`gh` CLI 不走代理，直连使用（AGENTS.md 约定）。

## PR 是否作为 triage 入口

**PRs as a request surface: no.**（PR 作为请求入口：**否**。此行是 `/triage` 读取的标志，保持英文原样；若将来要把外部 PR 纳入 triage 队列，把 `no` 改为 `yes`，并补充 `gh pr view/list/comment/edit/close` 的等价命令——仅统计 `CONTRIBUTOR` / `FIRST_TIME_CONTRIBUTOR` / `NONE` 身份的外部 PR。）

## 当 skill 说「publish to the issue tracker」（发布到 issue tracker）时

创建 GitHub Issue（`gh issue create`）；若是会话中顺带发现的非阻塞事项，登记到 `backlog.md`。

## 当 skill 说「fetch the relevant ticket」（获取相关工单）时

运行 `gh issue view <编号> --comments`。

## Wayfinding 操作（供 /wayfinder 使用）

**地图（map）**是单个 issue，**子工作项（child）**为挂在其下的工作项 issue。

- **建地图**：单个带 `wayfinder:map` 标签的 issue，正文承载 Notes / Decisions-so-far / Fog。`gh issue create --label wayfinder:map`。
- **建子工作项**：将 child issue 作为 GitHub sub-issue 挂到 map（对 sub-issues 端点调 `gh api`）。sub-issue 不可用时，把 child 加进 map 正文的任务清单，并在 child 正文顶部写 `Part of #<map>`。标签：`wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。认领后把工作项指派给执行的 dev。
- **阻塞关系**：用 GitHub 原生 issue dependencies。加边：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是阻塞方的数字 database id（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`）。dependencies 不可用时，在 child 正文顶部回退为 `Blocked by: #<n>, #<n>` 行。所有阻塞项关闭后工作项才解锁。
- **前沿查询（frontier）**：列出 map 的 open 子工作项（`gh issue list --state open`，限定在 map 的 sub-issue/任务清单内），去掉有未关闭阻塞项或已有 assignee 的；map 顺序中第一个优先。
- **认领**：`gh issue edit <编号> --add-assignee @me` —— 会话的第一个写操作。
- **解决**：`gh issue comment <编号> --body "<答案>"` → `gh issue close <编号>` → 把上下文指针（gist + 链接）追加到 map 的 Decisions-so-far。
