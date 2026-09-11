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

## #395 steer 插话徽标（实现 + 持久化修复，2026-09-12）

- **实现**（commit `605d2194`）：Message.User.viaSteer + MessageCardUser 两处统计栏 SteerBadge；文案 `chat_steer` ×15 locale（en Interjected / zh-rCN 插话，待用户裁决可调）；标记落点 = seedTranscript 播种（ChatRepositoryImpl steer=true）与 V2SseMapper `delivery=steer`。**顺带修**：V2 `session.input.admitted` 的 delivery 嵌在 `input` 下，原实现只读 `item`/顶层 → 该路径 queue/steer 档位全部失灵（queue 拦截同样受益）。
- **复验一（FAIL，渲染通过）**：忙碌长按发送键触发 steer，徽标 t+2s~t+12s 命中 `text="插话"` 且同气泡；但 **~16s 后丢失**——V2 REST 持久化载荷无 `delivery`（curl 实证仅 `{id,time,text,agents,type}`），SSE 静默 15.84s 触发 SessionStateService L3 兜底 REST 刷新（REST_AUTHORITY）重建消息，`@Transient` 标记被纯覆盖抹掉；重进会话/分页回补同理。证据 docs/acceptance/2026-09-12-395-steer-badge-verification.md + 证据目录。
- **持久化修复**（commit `d9a79767`）：`viaSteer` 去 `@Transient`（随 cached_messages payload 持久化）+`MessageMergeEngine.mergeMessageMeta` 与 `MessageEventHandler.upsertRestAuthority` 在 REST 权威覆盖 user 消息时保留既有 `true`；测试 `MessageViaSteerTest`（序列化往返/缺席默认 false）+ `MessageMergeEngineTest`（保留用例）。

## #395 复验二：持久化修复后 PASS（2026-09-12）

- 安装 `cc7fa9f8` 复验：忙碌长按 steer（logcat `Sent prompt … queueRow=false`）后 6 点采样 t+2/10/16/25/35/45s **全部命中 `text="插话"`** 且与 `STEERFIX5` 同气泡——越过 15.84s L3 兜底刷新点（窗口内 10 次 `L3 fallback refresh: 50 msgs` + `upsert n=50`，徽标不丢）。
- 退出会话重进徽标仍在；Room DB 直查 payload 含 `"viaSteer":true` → 持久化确证。
- 负向对照（空闲普通发送）无徽标；crash buffer 0 行。
- 结论：**#395 PASS**，转待用户验收。证据 docs/acceptance/2026-09-12-395-steer-badge-verification.md「复验二」节 + 证据目录 recheck2/。

## #390 复现核查：三指控不成立（#267 已覆盖）+ #401/#402 新缺口（2026-09-12）

- clean-context 模拟器复现（reverse 拆隧，只读）：**#390 三处指控均不成立**——① 拆隧 ≈5.5s 后出现「服务器已断开，正在重连…」条幅（Chat+会话列表+设置 tab，19/19 帧在）；② 转录 19 帧非空、logcat 无 EventDispatcher/releaseSessionData；③ 90s 无导航、全程停在会话页。→ 已被 #267「双界面条幅」覆盖，建议关闭（待用户拍板）。
- **新登记**：#401（P3 服务器管理 Home 界面无该条幅——#267 双界面声明范围外，非回归）；#402（P2 SSE 5min 冷却致非网络切换型瞬断恢复迟滞——reverse 回加后 HTTP 已 200，但 Connected 迟至 ~4m38s；冷却期 `runSseConnectionLoop` 只 `delay(30s)` 不发起连接，仅 Android 网络恢复回调 reset）。
- 证据：docs/acceptance/2026-09-12-390-disconnect-repro.md + 证据目录 55 文件。

## #403 system/message 标签遮蔽修复 + 复验 PASS（2026-09-12）

- **修复**（commit `e6a4a3e3`）：提取 `injectionKindLabel(kind)` 单一映射源；system 分支在**显式** kind（plugin/skill-catalog/agent-instructions）时用 kind 标签，`injectionKind=="system"`（无 source.kind）保持历史「工具目录已变更」语义。
- **复验 PASS**（APK `78e800a0`）：会话 a84edbf7 首张系统注入卡（rank 0，id …0f4ff178）标签「插件配置」（修复前「工具目录已变更」）；展开正文仍含 harness 字面（35651 字符）；`SysMsgDiag` 证实走 role==system 分支；crash 0。证据 docs/acceptance/2026-09-12-403-label-verification.md。
- 备注：全库 role=system 的 injectionKind 仅 NULL(58)/plugin(38)，无 "system" 值 → 「工具目录已变更」回退暂无可测样本（N/A），语义保留。

## 发现项盘点与排期（用户增补纪律，2026-09-12）

用户增补执行纪律（2026-09-12）：① 问题/bug 直接根因修复；② 可优化点综合评估后按最优方案优化；③ UIUX 改进先全网调研，再依调研结论优化。据此盘点本会话发现：

**A. Bug → 根因修复**
- #394 跳转高亮（渲染键与跳转键漂移 + jumpTo 固定 u_ 前缀错配）— 已修 + 模拟器复验 PASS。
- #393 搜索命中 BM25 角色偏置 — 已修 + 复验 PASS。
- #387 无 source.kind 的 system-reminder 注入文字墙 — defensive 渲染层嗅探 + 单测（无 live 样本）。
- #395 steer 徽标缺失 + ~16s 持久化丢失 — 已修 + 两轮复验 PASS（去 @Transient + 合并保留）。
- #403 system/message 显式 kind 被固定标签遮蔽 — 已修 + 复验 PASS。
- **#402 SSE 冷却误计连接级失败**（隧道/服务端瞬断恢复实测 ~4m38s）— **本轮根因修复**：冷却语义专指读超时（经 withTimeoutOrNull→null→break→流正常结束路径计数），连接级快速失败改由指数退避；待设备复验。
- 附带（并修）：#395 过程中发现 V2 `session.input.admitted` 的 delivery 嵌在 input 下未被读取（该路径 queue/steer 档位全失灵）。

**B. 优化点（综合评估）**
- #399 通用壳私有 DSH 卡 → 新 CHAT_MESSAGE_LIST 插槽（行为等价重构，通用壳零类型知识）— 已实现，待复验。
- 待评估：搜索 `archivedIds` 过滤对「双角色首屏」呈现的影响（复验员提示，非缺陷）。

**C. UIUX 改进（先全网调研）**
- #401 Home/服务器管理界面断连条幅 — **调研前置受阻**：web_search 端点返回 402 Insufficient Balance；M3 文档为 JS 渲染、web_fetch 取不到正文。按纪律**不擅动 UIUX 优化**，待检索恢复（用户可在 Settings > Plugins > Web search 更换端点）。

## #402/#404 根因修复复验 PASS（2026-09-12）

- **#404 复验 PASS**：冷启（force-stop+debug intent）DSH 进入 session-94365bc9 后 **t+4s** 底部 dump 即命中 `bash · sleep 1500`（运行中），t+8..66s 持续；进入后 66s 内 **0 次** JobsSnapshot/QueueSnapshot → 卡片来自 control baseline、未被 subscribed 清空。修复前旧包（75e89ad2）同流程 ≤30s 无卡（见 #399 报告 §BLOCKED）。
- **#402 复验 PASS**：OpenCode 4199 reverse 拆除后 8+ 次 `ECONNREFUSED`，`Entering SSE cooldown after` = **0**（阈值 5 / 5min），`Reconnecting in` 1000→2000→4000ms 指数退避；恢复 reverse 后 **~2.0s** 重连、断连横幅消失（旧行为 ~4m38s）。
- 回归：crash buffer 无 ocbeacon、FATAL=0。
- 证据：docs/acceptance/2026-09-12-402-404-verification.md + 证据目录。
