# GitHub Issues「隐藏 issue」能力调研报告

> 调研日期:2026-08-23 · 方法:所有结论对照 docs.github.com 官方文档(REST/GraphQL 参考、Issues/Discussions 文档)与 github.blog 官方 changelog 原文核实,未采信二手转述。无法从官方文档确证的点均显式标注「未确证」。

## TL;DR

1. **GitHub 没有原生的 per-issue「隐藏」能力**(无 hide/archive-per-issue);Issues 列表页对访客的默认视图(open 列表)目前没有官方途径被仓库级配置替换——2026-06 才进入 Public Preview 的「仓库 issue 保存视图」可给全员共享过滤视图,但改不了默认落地视图(未确证可改)。
2. 最接近"不出现在正常 issue 列表"的原生机制:**直接创建到专用子仓库**(零干扰、API 完全支持)> **label 排除视图**(`-label:` 语法官方支持,但只对主动使用该视图的人生效)> **创建即关闭**(open 列表干净,closed 列表/搜索仍可见)> **转为 Discussion**(UI 可单条转、无 API)。
3. API 视角:REST `POST /repos/{owner}/{repo}/issues` 与 GraphQL `createIssue` **都不接受 state 参数**(不能一步创建为 closed),需 create→close 两步;**transfer 只有 GraphQL `transferIssue` mutation**(REST 无端点);查重用的 REST search 端点**无 state 查询参数**、状态过滤只靠 `q` 限定符——不带 `state:`/`is:` 时 closed issue 同样命中(此默认行为为实测通行行为,文档未逐字写明)。

---

## 1. 各机制核实结果

### 1.1 原生「隐藏 issue」:不存在 ✅(否定性结论)

- GitHub Issues 官方文档对 issue 的全部「管理」动作目录为:Triage / Pin / Mark as duplicate / **Transfer** / **Close** / **Delete** / Clone——没有 hide、没有 per-issue archive。
  证据(任一 issue 管理页的官方目录结构):[Closing an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/closing-an-issue)、[Transferring an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/transferring-an-issue-to-another-repository)
- 社区功能请求线程长期存在且开放,侧面印证能力缺失:[API to delete and move issues(#24782)](https://github.com/orgs/community/discussions/24782)、[Ability to hide closed sub issues(#157305)](https://github.com/orgs/community/discussions/157305)
- 注意:这是「官方文档中不存在该功能」的结论(基于文档目录 + 社区请求现状),没有一页文档写"你不能隐藏 issue"。

### 1.2 关闭 issue:open 列表消失,closed 列表/搜索仍在 ⚠️ 部分符合

- 官方文档([Closing an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/closing-an-issue)):任何人可关闭自己开的 issue;仓库 owner/协作者/triage 及以上可关他人 issue;关闭时可选理由。
- closed issue 出现在 Issues 页的 Closed 标签页与搜索结果中(UI 常识 + 搜索限定符 `state:closed`/`is:closed` 官方存在,见下)。**没有文档途径让 closed issue 从 closed 列表消失**。
- REST 列表端点 `GET /repos/{owner}/{repo}/issues` 的 `state` 参数**默认 `open`**(官方文档原文 "Default: open")→ 走列表 API 的工具默认看不到 closed:[REST issues](https://docs.github.com/en/rest/issues/issues#list-repository-issues)
- 搜索端点行为见 §2.5:不带状态限定符时 closed 也命中。
- 附:关闭 ≠ 锁定。禁评机制是 lock([Locking conversations](https://docs.github.com/en/communities/moderating-comments-and-conversations/locking-conversations):锁定后仅 write access 者可评论);close 后仍可追加评论是通行行为(文档未逐字写明,未逐字确证)。

### 1.3 transfer issue:可用,但只在 GraphQL ✅

- **GraphQL 有官方 mutation** `transferIssue`("Transfer an issue to a different repository"),输入 `TransferIssueInput { issueId: ID!, repositoryId: ID!, createLabelsIfMissing: Boolean }`(最后者为"目标库不存在同名 label 时是否自动创建"):[GraphQL 参考 · issues 域](https://docs.github.com/en/graphql/reference/issues#transferissue)
- **REST 没有任何 transfer 端点**:[REST issues 参考](https://docs.github.com/en/rest/issues/issues) 的操作清单只有 Create/Get/Update/Lock/Unlock/List suggestions 等;transfer 一词仅出现在"issue 被转移后 GET 返回 301/404/410"的说明里。
- 官方 UI/CLI 文档([Transferring an issue to another repository](https://docs.github.com/en/issues/tracking-your-work-with-issues/transferring-an-issue-to-another-repository))核实的限制与行为:
  - **只能转移 open issue**;
  - 需要对**源仓库和目标仓库都有 write 权限**;
  - **只能在同一用户/组织的仓库之间**转移;**私有库 issue 不能转到公共库**(公共→私有方向文档未提及,未确证);
  - **评论与 assignee 保留**;label/milestone 按"名字相同 / 名字+截止日相同"在目标库匹配保留;
  - 原 URL 301 重定向到新 URL;被 @ 的人收到转移通知;
  - CLI:`gh issue transfer ISSUE OWNER/REPO`。
- 对本案:LeoNardo-LB/oc-beacon 与 oc-beacon-reports 同 owner,满足限制;但 GitHub App 的 user access token 是否具备转移所需权限未确证(见 §2.6)。

### 1.4 专用子仓库:机制完全支持,「常见做法」属社区模式而非官方推荐 ⚠️

- 机制上零障碍:创建 issue 到别的仓库 = 同一个 REST 端点换 `{owner}/{repo}` 路径(见 §2.1),或 GraphQL `createIssue` 换 `repositoryId`。**"直接建到子库"没有 transfer 的那些限制**(不需要 open、不需要双库 write——创建只要 pull access)。
- 官方文档**没有**把"上报/自动 issue 分流到子仓库"列为推荐实践——这个模式的存在依据是社区讨论(如 [#24782](https://github.com/orgs/community/discussions/24782)、[#2952 How to move existing issues into Discussions](https://github.com/orgs/community/discussions/2952))与大量项目的实际做法;「是否常见」无法用 docs.github.com 确证,只能说"社区流行、官方沉默"。
- 效果:主仓库 Issues 列表、计数、默认搜索、通知**完全零干扰**;查重搜索改 `repo:` 限定即可照常工作。
- 代价:spec 的"目标仓库固定为主库"决策需变更;GitHub App 需安装到新库;若子库**私有**,普通用户提交后拿到的 issue 链接 404(无读权限)、公开则出现于 profile(可接受性需维护者判断)。

### 1.5 label 过滤视图 / Issues 列表默认视图 ✅(语法)/ ⚠️(默认视图)

- **`-label:` 排除语法官方文档明确支持**:[Searching issues and pull requests](https://docs.github.com/en/search-github/searching-on-github/searching-issues-and-pull-requests#search-by-label) 原文示例 "in:body -label:bug label:priority matches issues … that lack the label 'bug'",并有总则 "Use a minus (hyphen) symbol to exclude results that match a qualifier"(注意:`no:` 系列"缺失元数据"限定符**不能**加 `-`)。
- 因此 Issues 列表页 URL 形如 `https://github.com/OWNER/REPO/issues?q=is:issue+is:open+-label:user-report` 有效,可书签化、可写进贡献文档。
- **「保存的默认视图」现状分层**:
  - 个人级:全局 Issues 仪表盘(github.com/issues)自 2025-05 起支持 saved views,仅本人可见:[changelog 2025-05-15](https://github.blog/changelog/2025-05-15-saved-views-on-the-issues-dashboard/);
  - **仓库级(关键)**:2026-06-25 起 **Repository Issues 页支持 saved views(Public Preview)**——triage 权限及以上可创建共享视图(官方举例即含 "Customer-reported issues" 这类场景),仓库所有人可用,入口在新 Issues 侧栏:[changelog 2026-06-25](https://github.blog/changelog/2026-06-25-saved-views-for-repository-issues-and-adjustable-row-heights-in-projects/)、[社区讨论 #200164](https://github.com/orgs/community/discussions/200164);
  - **但没有任何文档说明可以把某个 saved view 设为所有访客的默认落地视图**——直接访问 /issues 仍落在默认 open 列表(未确证可改;Preview 功能,行为可能变化);
  - GitHub Projects 的过滤视图(`-label:` 同样可用)是 Projects 界面的事,不影响仓库 Issues 页:[Filtering projects](https://docs.github.com/en/issues/planning-and-tracking-with-projects/customizing-views-in-your-project/filtering-projects)。
- 局限:issue 计数、closed 列表、无差别搜索结果、通知噪音都不受 saved views 影响。

### 1.6 Discussions:单条可转(UI only),直接创建有 GraphQL API ✅/❌

- **UI 可把单条 issue 转为 discussion**(需 triage 权限):[Moderating discussions → Converting an issue to a discussion](https://docs.github.com/en/discussions/managing-discussions-for-your-community/moderating-discussions#converting-an-issue-to-a-discussion),转换后内容自动带入、选择类别。
- **基于 label 的批量转换已于 2025-06-06 弃用**:[changelog 2025-05-22](https://github.blog/changelog/2025-05-22-deprecation-of-bulk-conversion-of-issues-to-discussions-via-labels/)(原文:仍可手动单条转)。
- **API 视角:不存在任何 issue→discussion 转换端点**。核查:[GraphQL issues 参考](https://docs.github.com/en/graphql/reference/issues) 无 convert 类 mutation;[GraphQL discussions 参考](https://docs.github.com/en/graphql/reference/discussions) 全文 0 处 "convert";REST API 目录无 discussions 类别(Discussions 走 GraphQL,见 [REST API 索引](https://docs.github.com/en/rest))。→ **存量 issue 的转换无法自动化**。
- **直接创建 discussion 有 API**:GraphQL `createDiscussion`,输入 `{ repositoryId: ID!, categoryId: ID!, title: String!, body: String! }`([GraphQL discussions 参考](https://docs.github.com/en/graphql/reference/discussions#createDiscussion))。Discussion 从不出现在 Issues 列表。
- 讨论**可被搜索**,但要走 GraphQL `search(type: DISCUSSION)`([SearchType 枚举](https://docs.github.com/en/graphql/reference/search#searchtype) 含 DISCUSSION);**REST `/search/issues` 不覆盖 discussions**——指纹查重若迁到 discussion 必须换 GraphQL。
- 适配性:discussions 没有 label/assignee/状态机(`createDiscussion` 输入即证),与本案"复用 needs-triage 标签的分诊流程"冲突;更适合社区问答而非机器错误报告管道。

### 1.7 其他机制核实

| 机制 | 核实结果 | 文档 |
|---|---|---|
| 删除 issue | 存在,但**仅 admin、永久删除**;GraphQL `deleteIssue` mutation 存在,REST 无对应端点 | [Deleting an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/deleting-an-issue)、[GraphQL issues](https://docs.github.com/en/graphql/reference/issues#deleteissue) |
| 锁定 issue | REST `PUT .../lock` 存在;只禁评论,**可见性不变** | [Locking conversations](https://docs.github.com/en/communities/moderating-comments-and-conversations/locking-conversations) |
| archive | 只有**整库 archive**(只读)与 Projects 条目 archive;**无 per-issue archive** | [Archiving repositories](https://docs.github.com/en/repositories/archiving-a-github-repository/archiving-repositories) |
| spam/最小化隐藏 | `minimizeComment` 类隐藏只作用于**评论**(spam/off-topic 等),issue 本体不可 minimize;反垃圾仅体现为 create 返回 422 "endpoint has been spammed" | [GraphQL issues · minimizeComment](https://docs.github.com/en/graphql/reference/issues#minimizecomment)、[REST create issue 状态码](https://docs.github.com/en/rest/issues/issues#create-an-issue) |
| pin issue | 存在,但效果相反(置顶更显眼) | [Pin an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/pinning-an-issue-to-your-repository) |

---

## 2. REST / GraphQL API 视角

### 2.1 创建时的可选"隐藏"手段

**结论:没有任何参数能让 issue"创建即不出现在列表"。** 可做的只有:建到别的仓库、建完立刻关、贴特定 label 供视图排除。

- REST `POST /repos/{owner}/{repo}/issues`([Create an issue](https://docs.github.com/en/rest/issues/issues#create-an-issue))
  Body 参数全集:`title`(必填)、`body`、`milestone`、`labels`、`assignees`、`issue_field_values`、`type`——**没有 `state`**。
  权限要点(文档原文):"Any user with pull access to a repository can create an issue";但 **labels/assignees/milestone/type 仅 push access 者有效,否则被静默丢弃(silently dropped)**——对本案 GitHub App device-flow user token(非协作者)是否会丢 `needs-triage` 标签,文档未按 token 类型细分,**未确证,建议实测**。
- GraphQL `createIssue`([参考](https://docs.github.com/en/graphql/reference/issues#createissue))
  `CreateIssueInput`:`repositoryId`、`title`、`body`、`labelIds`、`assigneeIds`、`milestoneId`、`issueTypeId`、`parentIssueId`、`projectIds`/`projectV2Ids`、`issueFields`、`issueTemplate` 等——**同样没有 state**。

### 2.2 "创建即 closed" = 两步

- REST `PATCH /repos/{owner}/{repo}/issues/{issue_number}`([Update an issue](https://docs.github.com/en/rest/issues/issues#update-an-issue)):`state: open|closed` + `state_reason: completed|not_planned|duplicate|reopened`(`duplicate` 可配 `duplicate_issue_id`)。
- GraphQL `updateIssue`(`state: IssueState` 或 `stateInput`,后者带 reason/duplicate 引用)。
- 效果边界:closed 后**仍在 closed 列表与搜索结果中**(见 §1.2)。

### 2.3 直接建到别的仓库

- 与 §2.1 完全相同的端点/mutation,换路径参数(`{owner}/{repo}`)或 `repositoryId` 即可;前提是 token 对目标库有创建权限(本案 = GitHub App 安装范围覆盖该库)。

### 2.4 transfer

- 仅 GraphQL `transferIssue(issueId, repositoryId, createLabelsIfMissing?)`;REST 无端点;CLI `gh issue transfer`。详见 §1.3。

### 2.5 查重搜索的默认状态行为(本案关键)

- REST `GET /search/issues`([Search issues and pull requests](https://docs.github.com/en/rest/search/search#search-issues-and-pull-requests)):查询参数只有 `q`(必填)/`sort`/`order`/`per_page`/`page`——**没有 state 参数**;端点描述 "Find issues by state and keyword",官方示例在 `q` 里显式写 `state:open`。
- 状态过滤只由 `q` 限定符承担(`state:open|closed`、`is:open|closed`,见 [Searching issues and pull requests](https://docs.github.com/en/search-github/searching-on-github/searching-issues-and-pull-requests))。
- **不带状态限定符时 closed issue 是否命中:实测通行行为是 open+closed 都返回,但官方文档未逐字写明这一默认值(未逐字确证)**。对本方案的含义:查重若沿用 spec 的"限定 open",任何"创建即关闭"或后来被关闭的报告都会漏查 → 重复建 issue;去掉 open 限定(或显式不限定状态)即可把 closed 报告纳入查重。
- 另一条与本案 token 直接相关的**文档明确规则**:GitHub App 的 user access token 发起的搜索**必须带 `is:issue` 或 `is:pull-request`,否则 422**。

### 2.6 token/权限小结

- 创建:pull access 即可;**贴 label 需 push access 否则静默丢弃**(user token 场景未确证,需实测)。
- 关闭自己开的 issue 任何人都可;关他人的需 triage+。
- 转移:双库 write + 同 owner;user access token 是否满足未确证。
- 删除:仅 admin。

---

## 3. 推荐方案排序(结合本案)

背景约束(取自 `docs/specs/2026-08-21-error-report-github-design.md`):目标仓库固定为 LeoNardo-LB/oc-beacon;GitHub App device flow + Issues: Read & write 的 user access token;标题前缀 `[user-report]` + `needs-triage` 标签;查重 = GitHub search API 搜指纹串(限仓库 + **open 状态** + 前缀);命中则追加评论。

### P1(推荐):专用子仓库直接创建(如 oc-beacon-reports,公开)

- **客户端改动量:最小**——同一套 REST 调用,只改目标 `{owner}/{repo}`(常量/配置一处);GitHub App 安装范围加上新库即可。
- **主库零干扰**:列表/计数/搜索/通知彻底干净,且不需要维护者改变任何浏览习惯。
- **查重完全可用**:search `repo:LeoNardo-LB/oc-beacon-reports` + 指纹串;新库没有"污染列表"压力,报告可以**保持 open**,spec 的 open 限定查重逻辑**不用改**;将来真的要关也只需去掉 open 限定。
- **维护者检索便利**:新库即检索面;label/前缀/正文指纹块流程原样保留。
- 代价:spec"固定仓库"决策变更;App 安装到新库;建议公开库(私库会导致用户拿到的 issue 链接 404)。「常见做法」属社区模式,官方文档无推荐背书(§1.4)。

### P2:主库保留 + `-label:` 过滤视图(可与任何方案叠加,客户端零改动)

- Issues URL `?q=is:issue is:open -label:user-report` 官方语法支持,可书签/写进 README;2026-06 Public Preview 的**仓库级 saved views** 可让 triage+ 建一个全员可用的"功能讨论"视图。
- 局限:**改不了默认落地视图**(未确证可改);closed 列表、全局搜索、通知噪音、issue 计数仍在。
- 查重零影响。**适合作为 P1/P3 的补充而非独立解**;若短期内不动客户端,这是唯一立即可用的官方机制。

### P3:创建即关闭(create → PATCH `state:closed, state_reason:not_planned`)

- 客户端改动:+1 次 API 调用,很小。
- 效果:open 列表即刻干净;但 closed 列表/搜索仍可见,`not_planned` 语义会进统计。
- **硬前提:必须同步修改查重**——去掉 spec 里的 open 状态限定(否则关闭的旧报告漏查、重复建 issue;REST search 无 state 参数、默认不限状态,去掉限定后 closed 可命中,见 §2.5)。closed issue 仍可被追加评论(锁定才是禁评机制),追评流程可用(通行行为,未逐字确证)。
- 定位:不想动仓库结构时的折中;与"分诊后再关"的自然工作流略有张力(报告一出生就是 closed)。

### P4:直接 `createDiscussion`(GraphQL)

- 客户端改动**大**:REST→GraphQL(创建 + 查重都要换栈;REST 无 discussions 端点类别,查重须走 GraphQL `search(type: DISCUSSION)`)。
- Issues 列表彻底无关、类别即组织;但**丢失 label/assignee/状态机**(`createDiscussion` 输入即证),needs-triage 分诊流程失效;issue→discussion 转换无 API,存量不可自动迁移。
- 定位:若未来想把用户报告做成"社区支持论坛"再考虑;不适合当前机器错误报告管道。

### P5:`transferIssue`(GraphQL)——兜底/迁移工具,不作主流程

- 先建主库再转移 = 两次调用 + 主库订阅者一次通知噪音;同 owner/双库 write 的限制本案虽满足,但徒增复杂度。**适用场景**:P1 落地前主库已积累的存量报告一次性搬去子库(评论保留、URL 重定向,见 §1.3)。

### P6(否决):删除(`deleteIssue`,admin-only 且永久——查重历史全丢)、整库 archive(全库只读)、spam/minimize(仅评论)、pin(效果相反)。

### 附加风险提示(与隐藏无直接关系但影响落地)

1. **label 静默丢弃**:REST 文档明确"无 push access 者 create 时 labels 被静默丢弃"。本案 user access token(非协作者)是否等价未确证——若 `needs-triage` 贴不上,P2 的 `-label:` 方案会失效,建议在目标库用真实 device-flow token 实测一次。
2. **GitHub App user token 的 search 必须带 `is:issue`**(否则 422,文档明确)。
3. saved views 为 Public Preview(2026-06),行为与可用范围可能变化,不宜作为唯一依赖。
