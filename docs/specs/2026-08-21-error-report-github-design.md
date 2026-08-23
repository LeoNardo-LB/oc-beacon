# 错误日志 GitHub 上报（Error Report to GitHub）设计 Spec

> 状态：待确认（/grill-me 会话 Q1–Q19 全部定案的产物）
> 日期：2026-08-21
> 来源：grilling 会话共识 + 三轮事实核查（仓库现状、GitHub 官方文档、日志分级审计）

## Problem Statement

**用户视角**：
- App 出错或崩溃后，用户只能在 Diagnostics 屏看日志，唯一求助手段是手动导出 diagnostics.txt，再自己去 GitHub 网页开 issue——门槛高、格式不统一、绝大多数用户不会做。
- 即使报了，同一 bug 被多个用户撞上时各自开独立 issue，重复报告无法归并，维护者要人工甄别。

**维护者视角**：
- release 构建开启 R8 混淆，但 mapping.txt 随临时 CI runner 一起销毁——用户报告的混淆堆栈永远无法还原。
- 既有日志分级存在集中病灶（SSE 重连风暴每次断连灌 5–8 条、同一失败双日志、per-event INFO 遗漏网）：用户上报的"最近 20 条错误"会被重连噪音填满，报告失去诊断价值。

## Solution

在 Diagnostics 屏新增"上报到 GitHub"能力（v1 纯手动触发）：

1. 用户通过 GitHub App device flow **输一次 8 位码**完成授权（注册时关闭 token 过期 → 永久有效），无需粘贴任何 token。
2. 上报内容仅 ERROR/FATAL，上报前**强制预览且可编辑**（已自动脱敏），明确告知将公开提交。
3. 客户端计算错误指纹：**已有人报过的错误不再新建 issue，而是向原 issue 追加带环境差异信息的评论**（设备/OS/版本/服务器摘要等 + 与原报告的 diff 高亮 + 本地频次统计）。
4. 附带两个前置修复：release workflow 留存 R8 mapping.txt artifact；日志分级审计发现的灌水/降级/丢堆栈问题全部修复。

## User Stories

1. 作为遇到错误的用户，我想在 Diagnostics 屏内直接把错误上报给维护者，这样不用手动导出文件再去网页开 issue。
2. 作为用户，我想只输一次 8 位授权码就完成 GitHub 授权，以后上报永远免登录。
3. 作为用户，我想在上报前预览完整报告内容并可以手动编辑，这样能删掉任何我不想公开的行。
4. 作为用户，我想让敏感信息（token/密码/IP/本地路径）在我看到报告之前就被自动脱敏，这样默认状态就是安全的。
5. 作为用户，我想在提交前清楚看到"此内容将公开提交到 GitHub"的明确提示，这样我的同意是知情的。
6. 作为用户，我想只上报 ERROR/FATAL 级别日志，这样不用在千条日志里挑拣。
7. 作为撞上已知 bug 的用户，我想让我的上报自动追加到已有 issue 而不是新建重复 issue，这样维护者的 issue 区不被刷屏、我的报告仍然被统计到。
8. 作为撞上已知 bug 的用户，我想让报告附上我的设备/系统/版本等环境信息及与原报告的差异，这样维护者能发现"只在某机型/某版本出现"的线索。
9. 作为用户，我想在提交成功后看到生成的 issue 或评论链接，这样能订阅后续进展。
10. 作为用户，我想让同一错误 24 小时内的重复出现不再重复评论，这样不会给维护者造成通知轰炸。
11. 作为用户，我想让上报走与现有网络栈相同的 TLS/代理配置，这样在我的网络环境下也能工作。
12. 作为网络不稳的用户，我想在上传失败后保留我编辑过的草稿并一键重试，这样不用重新编辑。
13. 作为授权失效（撤销/过期）的用户，我想收到明确的"请重新授权"引导而不是晦涩的 HTTP 错误，这样我能自助恢复。
14. 作为注重隐私的用户，我想让上报保持纯手动、绝无后台静默上传，这样我的数据外发永远由我决定。
15. 作为用户，我想让报告包含错误前后若干条日志作为时间线上下文，这样维护者能理解错误发生前发生了什么。
16. 作为用户，我想看到本次上报携带的环境参数清单（设备/OS/版本/服务器摘要等），这样我知道自己公开了哪些信息。
17. 作为维护者，我想让每个报告在正文中携带机器可读的指纹块，这样查重和统计可以自动化。
18. 作为维护者，我想让重复报告以"环境差异高亮 + 时间线 + 独立报告者计数"的形式追加评论，这样每条评论都有新信息量。
19. 作为维护者，我想让用户报告自动打上既有 triage 标签并带 [user-report] 标题前缀，这样现有分诊流程零改动。
20. 作为维护者，我想让 release 构建的 mapping.txt 被留存为 CI artifact，这样用户报告的混淆堆栈可以事后还原。
21. 作为维护者，我想让 SSE 重连风暴/per-event 灌水日志被修复，这样用户报告里的"最近错误"是真实错误而非噪音。
22. 作为维护者，我想让崩溃与非崩溃错误用不同指纹策略（崩溃按版本隔离），这样混淆名漂移不会导致错误归并。
23. 作为维护者，我想让错误日志在缺少异常对象时也带堆栈，这样报告不丢关键诊断信息。
24. 作为新装用户，我想在未授权时从上报入口被引导完成整个授权流程，这样不需要预先阅读文档。

## Implementation Decisions

### 范围与目标
- 目标仓库固定为维护者的公开 fork 仓库（LeoNardo-LB/oc-beacon），不做用户可配置。
- 触发方式：v1 仅手动（Diagnostics 屏内入口）；崩溃后自动提示上报、后台自动上报均不做（登记 backlog）。
- 上报级别：仅 ERROR/FATAL。

### 认证（GitHub App + device flow）
- 维护者注册一个 GitHub App：仅安装于目标仓库、仅 Issues 读+写权限、启用 device flow、**关闭 user access token 过期**（用户一次授权永久有效，除非主动撤销）。
- App 内流程：请求 device code → 展示 8 位码 + 引导打开 github.com/login/device → 轮询换取 user access token。
- device flow 换 token 需要 client_secret，无后端移动 App 只能嵌入 APK——已知情接受此风险（secret 单独无法完成任何用户级操作，必须配合用户授权）。
- token 存储复用现有 Keystore 加密体系（与 opencode 服务器密码同一模式：AES/GCM、v1: 前缀密文格式、优雅降级）。
- 认证层做成接口抽象，为将来升级（如 classic PAT 备选通道）留缝；v1 不实现 PAT。
- 授权失效（401）时引导重新走 device flow。

### 指纹与查重（重复性校验核心）
- 双轨指纹：
  - 非崩溃错误：`fp:err:<category>:<归一化 message>`——message 中数字/路径/ID 替换为占位符后参与指纹；message 语义稳定，**跨版本可查重**。
  - 崩溃：`fp:crash:<VERSION_NAME>:<异常类名>`——同一次构建混淆名确定，**同版本内去重；跨版本各建新 issue**（R8 混淆名跨版本漂移，事实上无法确认同一 bug，新版本本就该重新分诊）。
- 指纹嵌入 issue 正文的机器可读 fenced 代码块中。
- 查重：认证后的 GitHub search API 精确匹配指纹串（限定仓库 + open 状态 + user-report 前缀）；search 失败/限流时降级为直接新建 issue（不阻塞上报）。
- 命中重复：不新建，向该 issue 追加评论。

### 命中重复后的差异化评论
- 内容：
  - 环境差异块：设备（厂商/型号）、Android 版本+SDK、App 版本+flavor、语言、连接设置摘要、服务器清单摘要（名称+脱敏 host+探测到的 opencode 版本）、Runtime 内存水位——**上报时现采**，不改日志 schema。
  - 与原 issue 正文中机器可读环境块的自动 diff，差异字段高亮。
  - 本次错误时间线（该指纹相关的最近错误 + 前后上下文条目）。
  - 本地统计：该指纹近 7 天出现次数与首现时间。
  - 随机 install-id（UUID，首次上报时生成持久化）：用于统计独立报告者数。
- 防刷屏：同一 install-id 对同一指纹 24 小时内只评论一次（本地记账，超窗口再触发才追加）。

### 报告内容格式
- 新建 issue：标题 `[user-report] <错误摘要>`；正文 = 机器可读块（指纹 + 环境）+ 最近 20 条 ERROR/FATAL（每条带前后 3 条上下文）+ 免责说明；label 复用 needs-triage（不新增标签）。
- 所有内容复用现有导出的脱敏管道（认证头/token/IP/路径剥离 + 字段截断）后，再进预览。
- 正文长度控制在 GitHub issue 正文上限内，裁剪内联；不做 gist 附件（backlog）。

### 失败处理
- 网络/HTTP 失败：上报页内联错误 + 草稿保留 + 手动重试按钮；无后台重试队列。
- 401：明确提示重新授权；限流：提示稍后再试。
- 评论失败但 issue 命中：仅重试评论步骤。

### 模块划分
- 新增：GitHub device flow 认证模块（client_id 配置、device code 轮询、token 加密存取）。
- 新增：GitHub API 客户端（search / create issue / create comment，走现有 DI HttpClient，OkHttp 引擎，与更新检查同栈）。
- 新增：错误上报服务（指纹计算、查重编排、报告正文构建、24h 记账、install-id）。
- 新增：上报 UI（预览/编辑/提交/结果/失败态），挂入 Diagnostics 屏。
- 修改：Diagnostics 屏（上报入口）、诊断 ViewModel（上报状态流）。
- 修改：AppLogger 各调用点按审计结论修复（重连风暴降级、双日志去重、per-event 补 DEBUG 门控、7 处补 throwable、WebView 子资源错误门控、遗留诊断标签清理、V1/V2 parser 级别对称）——file:line 清单随实现 issue 走。
- 修改：release workflow 上传 mapping.txt artifact（与 APK 同批，90 天保留）。
- i18n：全部新文案按 15 语言工作流维护。

## Testing Decisions

**测试哲学**：只测外部行为（给定日志+环境+伪造 API → 期望结果），不测内部实现细节；纯函数直接单测。

**测试缝（seams）**——共两个，均为可复用或最高层：
1. **错误上报服务边界（主缝，最高业务缝）**：GitHub API 客户端在此处被伪造（fake）；覆盖：指纹双轨计算、查重命中→评论 / 未命中→建 issue、search 失败降级、24h 防刷、正文构建与裁剪、环境 diff、install-id 行为。
2. **GitHub API 客户端（薄层，Ktor MockEngine）**：device flow 轮询时序（pending→slow_down→success）、search/issue/comment 三端点的请求形状与错误映射（401/403 限流/网络错）。先例：现有 V1/V2 API 客户端测试同模式。

纯函数单测：指纹归一化（数字/路径/ID 占位替换）、环境块 diff。ViewModel 层用 Turbine 测状态流（先例：现有 ViewModel 测试）。

**人工验证**（按 verification-requirements 维度 5）：真机 E2E——授权流、真实建 issue、重复触发命中评论、预览编辑、失败重试；在目标仓库用专用测试 issue 验证后清理。

## 决策更新（2026-08-23，用户定规）

1. **标题区分度**：`<错误摘要>` 实现为 `category: message`（message 折叠单行、**中段截断**保头 56 + 尾 24）
   + **指纹 8 位十六进制签名后缀 `(#xxxxxxxx)`**（SHA-256 前 4 字节）——不同错误标题必不同（硬保证），
   同一错误重复上报标题一致（与查重归并对齐）。见 `ErrorReportService.issueTitleForError`。
2. **不做 issue 隐藏**：调研（`docs/research/2026-08-23-github-issue-hiding.md`）确认 GitHub 无原生
   per-issue 隐藏；子仓库/创建即关闭等方案均否决——报告留在主仓库，靠标题区分度保证列表可扫读。
3. **凭据注册**：提供 `scripts/setup-github-report-app.sh` 向导（注册 App → 捕获 ID/Secret → 写
   local.properties + CI secrets → 安装到仓库 → device flow 端点自检 → 真机验证指引）。

## Out of Scope

- 崩溃后自动提示上报 / 后台自动上报（backlog）
- 全量日志 secret gist 附件上传（backlog；issues API 本就无附件端点）
- classic PAT 认证通道（认证层留缝，不实现）
- OAuth web application flow
- DiagnosticLogEntry schema 增加结构化环境字段（上报时现采已足够）
- crash_<ts>.txt 旁路文件作为数据源（未走脱敏管道，不接）
- 上报到 upstream 仓库 / 用户自定义仓库
- 报告携带性能/perf 数据（release 构建无 perf 监测，拿不到）
- 逐条日志的服务器归属标注

## Further Notes

- **GitHub App 注册清单（维护者操作，一次性）**：Settings → Developer settings → GitHub App → New：名称/主页随意；Callback URL 留空（device flow 不需要）；**勾选 "Expire user authorization tokens" 关闭过期**（默认即关）；**勾选启用 Device Flow**；仓库权限仅 Issues: Read & write；安装到 LeoNardo-LB/oc-beacon；记录 Client ID 与 Client secret 填入 App 构建配置。
- 官方文档已核验：GitHub App 原生支持 device flow（无需联系 support）；token 过期是可选功能可关闭；fine-grained PAT 对他人仓库只读、不可用于本方案（这也是否决 PAT 路线的根因）；issues REST API 无附件端点。
- 日志分级审计结论：583 处调用点整体健康（d 43%/i 14%/w 20%/e 23%，零处把取消记为错误），病灶集中且全部纳入本次修复；与上报无关的改进登记 backlog 不跑题。
- 实施顺序：日志分级修复（上报质量前置）→ workflow mapping 留存 → GitHub App 注册 → 认证模块 → 指纹+查重+上报 UI → 验证。
