# post-refactor-backlog-triage（2026-09-11）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §一 架构重构后 backlog 全量三分类（2026-09-12）

背景：#391 ServerAdapter 九切片代码全部落地（HEAD 26ba794f）；用户要求盘点「架构重构后哪些卡片仍需做」。逐卡只读代码取证（未凭卡片文字），三分类如下。

### A 已实现、仅待验收/迁移（#391 家族）
- **#391**（9 切片代码就位）、**#396**（devDebug 4 项 lint 清零：HiltEntryActivity 迁 src/debug、3 处 getString 收敛 EventTimeString）、**#397**（:lint-checks 四条规则接入 lintChecks，四规则 baseline 0 条）、**#398**（按代词汇表 + V3 五族处置全定；assistant/attempt 静默、feedback log-only、subagent/catalog 双源降级为设计性决策）、**#400**（A/B 节真机已过，C/D/E 见本批模拟器验收）。
- **#399 唯一残余**：通用 `ChatMessageList` 内 private `DshJobTimelineCard`（未走消息列表插槽）。裁决建议：spec 切片5 冻结「5 插槽」契约，新增第 6 插槽属契约变更需 spec 修订；该卡经 JOBS_PUSH 能力位门控、复用通用 EventCard + 通用 JobView，实为「通用 pinned jobs 时间线」而非服务器类型界面泄漏 → 建议按命名瑕疵接受，或另开 spec 修订加插槽；不在本批擅改契约。

### B 真实遗留、与本架构无关
- **#394** 跳转 5s 高亮失效 → 本批修复（§二）。
- **#393** 全部过滤下 AI 行不可见 → 本批修复（§二）。
- **#387** 无 source.kind 的注入按用户气泡墙渲染 → 本批修复（§三）。
- **#395** steer 上屏消息缺徽标：待做——**文案待用户裁决**（「插话/注入中」）；需徽章状态随消息携带（seedTranscript 播种 + V2 delivery=steer；i18n ×15 + MessageCardUser 两变体）。因含用户裁决项未擅动。
- **#392** V1 sheet fling 未隔离：待做——**需 V1 靶机**；当前环境仅 V2(4199)/DSH(3080)，无 V1 服务器 → 无法复现/验证，保留待 V1 环境。
- **#390** 断连时会话页空白/弹回无重连提示：待做（P3）——ServerLinkBanner 已在场，需定位 EventDispatcher releaseSessionData 后的具体路径。
- **#388** V2 注入推送条件不明：待服务端历史 API 与 dsh web 对照定责；定责前不动客户端。

### C 外部阻塞 P4（维持）
- **#381**（DSH 远程凭据探知）、**#352**（上游恢复动词）、**#350**（V1/V2 归档写端点；最新裁决删除优先）、**#332**（结构化 spill 信号）、**#288**（tool-workflow 事件源）、**#146**（上游 PR 流程门槛）、**#345**（MIUI 平台观察，真手指复现才升级）。

### 结论
- 重构后**无新增待办**：#394/#393/#395/#392/#387 均为独立遗留，不会被架构收尾自动闭合。
- #391 家族仅剩「#399 命名瑕疵裁决」+「#400 E2E 收尾」两项非代码事项。


## #394 / #393 修复（架构重构后复验遗留，2026-09-12）

- **#394 根因**：渲染 Lazy item key 用 `turnGroups[rawIndex].first()` 推导 assistant 的 `t_` 键（旧 :1367），而高亮 key 在 Displayed 相位用 `rawMessages[rawIndex+1]` 推导（旧 :788）——两处公式漂移，assistant 目标永不命中；且相位回调在异步加载窗口内查 `displayItems` 可能为空 → 静默不设键。**修复**：统一 top-level `chatEntryKey(turnGroups, rawIndex, message)`（渲染与高亮同源）；相位只记目标 `msgId`，高亮 key 下沉渲染期 `remember(highlightedMsgId, displayItems, turnGroups)` 推导（异步就绪后自动补键）。新增 `ChatEntryKeyTest` 三例回归；加 Highlight set/clear DEBUG 观测点（免视觉验收仪器）。
- **#393 根因**：逐命中行改造后组内仅按 BM25 rank 排序，短文档偏置使 user 提示恒靠前、AI 命中沉到 240dp 折叠区下方。**修复**：SessionListScreen 组内按角色交错（users/agents 各自保持 rank 序，u,a,u,a…），两角色皆落首屏。
- 证据：`:app:compileDevDebugKotlin` ✓ · 全量单测 ✓ · `:app:lintDevDebug` "Lint found no new issues (257 warnings, 8 hints)" ✓ · commit `99f6430f` + `3a663e8e`；模拟器复验见 §四。

## #387 无 source.kind 注入折叠（2026-09-12）

- **根因**：V2 skill-catalog / 上下文刷新类注入按普通 user 消息投递、**无 source.kind**；ChatMessageList 仅当 `Message.User.injectionKind != null` 才走 EventCard 折叠（旧 :1699）→ 整屏蓝色气泡文字墙（#385 只修了有 source.kind 的 DSH 路径）。
- **修复**：新增 domain 纯判定 `SystemInjection.isPureReminder`（整条闭合 `<system-reminder>…</system-reminder>` 块；「闭合块+真问句」混合消息不折叠以防吞正文），渲染单点嗅探「首段以标记开头」再全文判定，命中置 kind="context" 复用既有 `chat_injection_context`（上下文注入）折叠卡。回归 `SystemInjectionTest` 四例。
- **复现限制**：扫描本机 4199 全部 50 会话 REST 消息 0 条 system-reminder（注入若经 SSE inbox 且不落 REST 历史，则本地播种消息为唯一载体）；DSH harness `system/message` 走 source.kind 路径不受影响。该修复为渲染层防御性单点，live 复现待注入源再验。

## #394 复验二：真根因（跳转键前缀）与修复（2026-09-12）

- 模拟器复验（clean-context）结论：**#393 PASS**（词 test 首组首屏 用户 3 行 + 智能体 2 行，u,a,u,a 交替）；**#387 NOT-REPRODUCIBLE**（DB 纯 <system-reminder> user 消息 10 条但 10/10 带 source.kind，无 kind 样本 0 条）；**#394 FAIL 并暴露更深根因**。
- **#394 真根因**：initial-jump（ChatMessageList pendingJumpTarget effect）对全部命中统一调 `JumpNavigationController.jumpTo`，该方法固定 `targetKeyPrefix="u"`；assistant 的 Lazy item key 是 `t_<turn 首条 assistant id>`（chatEntryKey）→ `u_<assistantId>` 永不匹配 → `findJumpTargetItem` 恒 null → 「重定位 idx=1（null=3）」×14 → 「布局稳定 超时」→ 相位 Failed，永不 Displayed。故 5s 高亮的相位钩子完全不可达（首修只保证「到 Displayed 后键同源」，必要但不充分）。
- **修复**（commit `86adacde`）：新增本地 `jumpToResolved(displayItemIndex, targetMsgId)` 按角色分流——user→`jumpTo(msgId, idx)`（u_），assistant→`jumpToTask(idx, turn 锚点)`（t_；锚点 = `turnGroups[ri].first()`，兼容多 assistant 长轮次）；jumpToMessage（快速导航）与 initial-jump 统一走该入口。
- 对照证据：用户命中 `target→set key=u_→clear`（5.001s）正常；assistant 命中修复前 0 条 Highlight。
- 附带发现（复验员）：`the`/`的` 的两角色命中集中在归档会话 session-94365bc9，被 SessionListScreen `archivedIds` 过滤，UI 首组只剩 assistant——归档过滤对「双角色首屏」验证有影响。
- 待：修复后定向复验见 §五。

## #394 修复后定向复验：PASS（2026-09-12）

- 前置：APK `0cb9147e` 覆盖安装（未卸载），已装 md5 一致；OpenCode 4199 debug intent。
- assistant 命中（搜索 test 首组「验收测试会话AB」，msgId=`msg_02ac03bff001xNnNxA40s2okwL`）：
  `jumpToResolved assistant anchor=msg_02ac03bf hit=msg_02ac03bf` → `jump: Displayed` → `Highlight target msgId=…` → `set key=t_msg_02ac03bff…` → `clear key=t_msg_02ac03bff…`（set→clear = 5.000s）。
- 负向断言：assistant 全文无「布局稳定 超时」/「重定位」/「null=」/「TimedOut」——旧空转全消失（修复前 14 次重定位 + 超时 Failed）。
- user 对照：`set key=u_`→`clear key=u_`（5.001s），无回归。
- 回归：crash buffer 0 行；app 进程全程存活。
- 证据：/tmp/acc-fix/394-recheck-{assistant,user}.txt + \*-raw.txt；文档 docs/acceptance/2026-09-12-fixes-394-393-387-verification.md「复验二」节。
- 判定：**#394 修复成立**（相位钩子 + 键同源 + 角色分流三层齐），转待用户验收。
