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

## #390 转待验证 + #401 调研降级评估 + #404 残留边界自检（2026-09-12）

- **#390 转待验证**：模拟器复现已证三指控（无横幅/转录空白/弹回管理页）均不成立、#267 覆盖；建议用户验收后关闭。
- **#401 调研降级评估**：web_search 端点 402，web_fetch 对 M3/M2 文档（JS 渲染）无正文；仅取到 Nielsen Norman Group「Visibility of System Status」（十大启发式）与错误信息指南静态正文。**评估结论**：Home 每张服务器卡已显示「正在连接…/取消」，已满足「系统状态可见」；是否再加全局条幅属**一致性 vs 冗余**取舍（#267 双界面为 Chat/会话列表，Home 为第三面）——建议保留 #401 待用户裁决/检索恢复后再定，不擅改。
- **#404 残留边界自检**：正在定点测试「断连期间任务结束 → 重连后是否残留陈旧运行中卡」（见后续补记）。

## V1/V2 服务器启动 + 遗留四项复证（2026-09-12）

- **V1 启动**（用户授权，按 docs/device-testing.md §V1 配方）：`/tmp/v1srv` XDG 隔离，`opencode` 1.18.27 @ `127.0.0.1:4198`（Basic auth opencode/leo12321），以**受管后台任务**常驻（nohup setsid 会被工具会话连带回收）；造测试会话 `v1-sheet-test`（16 消息/13 user）。V2 `127.0.0.1:4199` 原有。
- **#392 NOT-REPRO**（V1 靶机）：内容区向下快 fling 18 次（中部/顶部极限/30–50ms 超快/慢拖/向上/加载窗口 0 与 +0.15s）**0 次收起**，V2 对照同构 → 非 V1 特有。真缝隙=**标题带**（dragHandle 459–490 + 标题行 490–~706）下滑仍收起，违反 #379「仅手柄/点外/返回」意图（边界 y≈706=列表首项顶）；根因=nestedScroll 只收可滚动子节点派发，标题带无滚动子节点直达 sheet anchoredDraggable。**另立 #405（UIUX，按纪律先调研；web_search 402 受阻）**，证据 docs/acceptance/2026-09-12-392-v1-sheet-fling.md。
- **#388 定责（宿主侧）**：V2 `/api/session/{id}/instructions/entries` 对新旧会话**均为空**；抓 `/api/event` 90s，新建 oc-beacon/home 会话仅 `session.created`，**无注入帧** → 新会话零注入是**服务端未推送**（疑似工作区级一次性），非客户端漏收；定责前不动客户端成立。
- **#387**：V2 无 live 样本（与 DB 扫描结论一致），defensive 嗅探+单测保留。
- **#350 事实更新**：V1 1.18.27 `PATCH /session/{id}` **真支持 `time.archived` 归档写入**（响应+GET 均落时间戳，非 SPA HTML；`archived:0` 可清），**推翻旧「V1 无任何更新端点」判定**；V2 openapi 119 路由仍 0 归档端点。按本卡最新裁决（删除优先）未接线，待用户重裁。

## #405 内容根手势块 + 抽屉非滚动带定论 + V1/V2 遗留收口（2026-09-12）

- 实现：手势块自标题 Row 上移**内容根 Column**（`sheetNonScrollableDragBlock`，Main 趟仅消费未被可滚动子节点消费的向下位移），4 个 sheet 统一（QuickNavigateSheet / PendingSheets / ModelPickerDialog / AnnotationInputSheet）；commit `ae52aeba`。compile + 单测隔离重跑 + lint（257 warnings / 8 hints，0 new）全绿。
- 设备复验（模拟器 emulator-5554，V1 4198 + V2 4199，clean-context 子代理，装机 APK md5 `fea3aad2a4e5145821c890f30c441870`）：总判定 **PASS**。内容区（≥538）下滑不收起（541/600/670/700 VISIBLE）；收起三通道正常（手柄 477 下滑 / scrim / Close）；列表滚动、条目跳转、上滑到底正常；crash buffer 0 字节；V1/V2 行为一致。证据 `docs/acceptance/2026-09-12-405-content-root.md`（v2）+ 同目录 dump/截图。
- **几何定论（原假设证伪）**：可见手柄条 y=519~526（x=503~576，像素探针），手柄槽 = [506,538]（12dp），M3 手柄 48dp 触摸目标 = [459,585]，内容 Column 顶边 = 538。故 #405 复验时判为「残留带」的 490~535 **不是**标题/空白带，而是**手柄本体（槽 + 触摸扩张）**——下滑收起属 #379 设计内。像素探针与源码 dp 换算逐像素吻合（SheetGestures.kt:51-60 的 5dp+28×3dp）。
- 结论：#405 的「标题带」= 内容区 ≥538，自 `730d5016`（标题 Row 版）起即已受保护；`ae52aeba` 的价值在**统一覆盖内容根的全部非滚动带**（含列表空/加载态与其余 3 个 sheet），实测无回归。
- 否决的旁路方案：`contentWindowInsets` 去顶部 safeDrawing — 实测本环境顶部 inset = 0（sheet 视觉顶边=506=手柄槽顶），该改动为 no-op；为避免引入未验证的行为变更已 revert，从未装机。
- #401 调研（web_search 仍 402，降级 curl 直连一手源）：M3/commonMain 93 组件中无 Banner/InlineMessage；Google 官方示例 Now in Android 对离线用 `duration = Indefinite` 常驻 snackbar；本仓库既有 `ServerLinkBanner`（#267）已用于 Chat/会话列表。**建议**＝仅当活动服务器非 Connected 时在 Home 顶部条件渲染既有 ServerLinkBanner（复用不新增组件），待用户拍板。见 `docs/research/2026-09-12-ux-research-405-401.md`。
- V1 runbook 补正（`docs/device-testing.md` §V1）：AI 工具调用上下文里 `nohup setsid … & disown` 仍会被连带回收 → 必须用受管后台任务；补「造测试会话配方」与「V1 `PATCH /session/{id}` 支持 `time.archived`」实测。
- 新登记卡片：#406（快速定位跳转后目标未居中，P3 `ui` `chat`）、#407（`RenderSupplyCoordinatorTest` 全量族跑间歇超时 T8/T11，P3 `test`）。

## 已完结卡片迁入（2026-09-12）

### **#405 快速定位抽屉「标题带」下滑仍收起——#379 隔离未覆盖非列表头部（UIUX 待调研）** `ui` `sheet`
  - 内容区 fling 已隔离（V1/V2 复核 NOT-REPRO）；但起点落在 sheet 顶部非列表带（dragHandle 459–490 + 标题行 490–~706）下滑仍收起，违反 #379「仅手柄/点外/返回收起」设计意图（实测边界 y≈706=列表首项顶）。
  - 根因：sheetContentGestureIsolation 挂在内容 Column，但 nestedScroll 只接收可滚动子节点（LazyColumn）派发，标题带无滚动子节点 → 直接进 sheet anchoredDraggable。修法需 pointerInput 消费 header 竖向拖拽或等价；属 UIUX，按纪律先全网调研（当前 web_search 402 受阻，降级 web_fetch）。
  - → docs/acceptance/2026-09-12-392-v1-sheet-fling.md
  - 2026-09-12 二次修复（commit ae52aeba）：手势块由标题 Row 上移内容根 Column（sheetNonScrollableDragBlock：Main 趟仅消费未被可滚动子节点消费的向下位移），4 个 sheet 统一；compile + 单测 + lint 全绿；本次复验覆盖旧残留带 490/500/510/520/530/535。调研依据 docs/research/2026-09-12-ux-research-405-401.md。
  - 2026-09-12 内容根版（ae52aeba）设备复验 PASS（V1 4198 + V2 4199，clean-context 子代理）：内容区 ≥538 下滑不收起（541/600/670/700 VISIBLE）；手柄槽 [506,538]（可见条实测 y=519~526）与 M3 48dp 触摸目标 [459,585] 内下滑/点击收起属 #379 设计内——初判的 490~535「残留带」经像素探针 + 源码 dp 换算互证为手柄本体，非缺陷；收起三通道 / 列表滚动 / 条目跳转 / 上滑到底 / crash 0 全 PASS。证据 docs/acceptance/2026-09-12-405-content-root.md（v2）。转待用户验收。
  - 迁入依据：用户 2026-09-12 裁决：按 M3 特性收敛，不再纠结 490~538 手柄区；内容根手势块 ae52aeba 复验 PASS（backlog.sh migrate 2026-09-12）

### **#388 V2服务端注入推送条件不明：今日新会话零注入** `chat` `v2` `server`
  - 同一服务进程（4199，9-7 22:17 起未重启）下：05-40 前后的 ack 会话有插件配置/工作区指令注入，09-14 后新建会话（leo-tkp 与 oc-beacon 工作区各一，含首轮 hi/1+1 提问）零注入事件（InjCard 全程 kind=null，转录顶无卡）。注入到底何时推送（每工作区一次性？目录变更才推？）未定；需以服务端历史 API 与 dsh web 同会话对照定责（服务端没推 vs 客户端漏收）。定责前不动客户端。证据：/tmp/n1_*.png n2_top_injections.png InjCard logcat
  - 2026-09-12 宿主侧定责（V2 4199，beta-19086）：抓 /api/event 90s + 新建 oc-beacon/home 会话，仅 session.created（无 inbox.enqueued/注入帧）；/api/session/{id}/instructions/entries 对新旧会话均为空；/session/{id}/context 仅返回会话消息。→ 新会话零注入是**服务端未推送**（疑似工作区级一次性），非客户端漏收；定责前不动客户端的前提成立，建议按服务端行为归档/待上游说明。
  - 用户 2026-09-12 裁决：定责完成，按外部前提结案（服务端未推送，无客户端可修点）。
  - 迁入依据：用户 2026-09-12 裁决结案：定责完成——V2 服务端未推送注入（工作区级一次性），无客户端可修点（外部前提性质）（backlog.sh migrate 2026-09-12）

### **#350 V1/V2 归档 API 接线——统一归档面收尾（统一审计批 4）** `v2` `archive`
  - 方向(若端点就位):V2ApiClient.updateSessionFields 补归档真线面(现仅 title 走 rename,归档字段 no-op 回 getSession);ServerCapabilities V1/V2 archiveSupported 翻 true→长按菜单归档项+已归档折叠区自动统一(能力位门控现成)
  - **2026-09-07 深夜探针定音(否定)**:服务器修复后实测——opencode2 beta-19086 自家 OpenAPI(/openapi.json,119 路由)**零归档端点**(无 /archive、无 home 域、PATCH /api/session/{id}=404、POST/PATCH /archive=404);审计前提「官方 web 有 archiveHomeSession」对本服务器版本不成立。V1 PATCH /session/{id} 仅 V1 面文档、本环境无 V1 服务器可证
  - **前提**：上游 opencode 服务器发布归档端点(OpenAPI 出现 archive/home 域)——届时报错即改+真机验收;环境已修复留档:坏因=postinstall 未跑完(stub 占位),本地平台包完好,`node postinstall.mjs` 离线修复,服务已恢复监听 4199
  - **裁决优先级（2026-09-09 定规）**：以节点 2 最新裁决为准——删除优先，归档仅在删除无 API 的面使用；V1/V2 已有删除 API，上游归档端点就位≠自动接线，届时须先回用户重裁
  - 前提加固（2026-09-10 端点考古）：V1-4198 无任何会话更新端点（PATCH/PUT 落 SPA 兜底 200 HTML）；V2-4199 无 PATCH /session（rename=POST /session/{id}/rename 单动词）；两服均无归档写入通道——归档接线前提（服务器暴露 time.archived 写）在两现行版本均不成立，维持 P4 待服
  - 2026-09-12 V1 靶机复证（4198/opencode 1.18.27）推翻旧判定：PATCH /session/{id} 支持 time.archived 真写入（响应+GET 均落时间戳，非 SPA HTML）——V1 归档通道存在；unarchive 传 null/0 均不生效（该版本疑似仅置位）。V2（4199）openapi 119 路由仍 0 归档端点。按本卡最新裁决（删除优先）未接线，待用户重裁。
  - 2026-09-12 复测更正（V1 4198/1.18.27，临时会话实测后已 DELETE）：PATCH /session/{id}  语义为「置时间戳」；传 0 **会写入 0**（GET 回读 archived=0，等效未归档/可清空），传 null 被忽略（保留原值）。上一条「null/0 均不生效、疑似仅置位」不准确，特此更正。V2（4199）仍 0 归档端点。
  - 上条更正补字（shell 反引号吞字）：被 PATCH 的字段名是 time.archived（请求体 {"time":{"archived":<ms|0|null>}}）。
  - 用户 2026-09-12 裁决：选项 a——维持不接线（删除优先）。本卡据此结案。
  - 迁入依据：用户 2026-09-12 裁决选项 a：维持不接线（删除优先），归档通道即便在 V1 存在也不接线（backlog.sh migrate 2026-09-12）

## 用户第二批裁决落地：#392 深查 / #387 对齐 web / #408 横幅顶推修复 / 反馈归卡约定（2026-09-12）

- **反馈归卡约定（用户定规，写进 backlog.md 头部操作纪律）**：用户对某张卡片的反馈/裁决经 `backlog.sh note <N>` 记入**该卡片**明细，**不另开新卡**承载反馈；仅当反馈引出**新的独立缺陷**时才另立卡片并在两卡明细互相引用（#401→#408 为先例）。
- **结案**：#388（定责=服务端未推送，外部前提性质）、#350（用户选项 a：维持不接线，删除优先）迁入 journal；#401 按用户裁决结案（Home 面确实不需要条幅）。
- **#392 深查（用户不接受仅 NOT-REPRO）**：clean-context 侦查穷举整条链路（组件签名 / 抽屉几何 / 两道手势防线 / 入口门控 / jumpTargets 数据源 / 程序性 dismiss）→ **未发现任何按服务器类型或服务器派生状态分叉的代码点**；lint 白名单门禁（ServerTypeWhitelistDetector）从结构上禁止 Chat UI 引用 ServerType。结论=**V1/V2 同一套逻辑/容器**；差异最可能是**构建时间差**（#405 指针层兜底 2026-09-12 04:18/04:41 落地，用户反馈同日）。唯一真实 V1/V2 输入差异=会话内容量（决定抽屉列表是否越过 75% 屏高可滚动边界），但两种情形两道防线均闭环。建议：核对用户两端 APK 构建 commit；必要时补 2×2 内容量矩阵复测。
- **#387 调研 + 实现（用户裁决：仿照 web 端逻辑）**：dsh web 靠 source.kind 字段（≠user 折叠为 context 节点，client.js:6048-6066）、opencode 靠 text part 的 synthetic 字段（synthetic part 在用户气泡隐藏，message-part.tsx:1198-1200；生产端 reminders.ts:26-48）——**两端都不做内容嗅探**。我们的 V2 服务器两类字段都不发 → 唯一可行路线=**字段优先 + 嗅探兜底**。实现：嗅探下沉到映射单点 DshEventMapper.mapUserMessage（无 kind 且整条恰为一个闭合 system-reminder 块 → injectionKind=context），实况/通知/未来消费者共用；渲染层对历史 Room 行的同判据兜底保留；新增单测（纯块→context、混合→null）。后续方向（卡内登记、不另开卡）：① 对齐 dsh 的 form 结构化展开体；② 对齐 opencode synthetic 的 part 级混合拆分。
- **#408（P2，由 #401 反馈引出）根因修复 + 设备复验 PASS**：横幅自身 statusBars padding（本机 128px）与下方 TopAppBar 默认 windowInsets **各吃一次状态栏** → 内容被顶推 195px（横幅条仅 66px）。修复=ZeroTopAppBarWindowInsets + ChatTopBar 增 windowInsets 参数 + ChatScreen / SessionListScreen 在横幅可见时归零；复验顶推 **195→67px（=横幅条高）**，Chat 与会话列表两面一致，服务器恢复后横幅消失。证据 docs/acceptance/2026-09-12-408-banner-push.md；提交 f2174f10。
- **#406 侦查结论**：**不是 #391 重构回归、也不是顶对齐→底部的语义翻转**——成功跳转的终态至今=顶对齐（JNC 渐进收敛；JNC 落点逻辑最后一次行为变更是 08-21 夹持收场，09-10/11 服务层重构未触及）。落底两出口：① #394 修复前 assistant 命中失败（u_ 键 vs t_ 键）→ 超时 Failed 停在初始底部对齐位（窗口=08-31 进入即跳转特性至 09-11 22:34 修复提交；v0.3.0 早于此无该路径）；② 夹持收场——目标下方（更新侧）不足一屏时顶对齐物理不可达，接受低位 Displayed（本地复验「高亮正常 + 两次落底」与此吻合）。若采纳 #406「居中」：只把 computeGap / settled 判据 / 900ms 稳定窗口三处目标改为居中，其余机制不动；JNC 是承重墙，须真机三态复验——待用户裁决。

## 已完结卡片迁入（2026-09-12）

### **#392 Sheet fling 手势隔离在 V1 未生效——快速定位抽屉快速下滑仍收起（#379 回归面）** `sheet`
  - 用户自助验收 ⑥ 顺带发现（2026-09-10）：#379 SheetGestures 内容手势隔离（fling 不收起+手柄收起）在 V2 验过，但 V1 服务器上 QuickNavigate 快速下滑 fling 仍会让抽屉收起——服务器类型交互统一铁律（ui-conventions §1）违背。疑点：sheet 组件按 server type 分叉 or V1 会话内容高度/嵌套滚动差异绕过隔离。
  - 2026-09-12 V1 靶机复现（4198/1.18.27）判定 NOT-REPRO：内容区向下快 fling 18 次 0 收起，V2 对照同构；V1 无 server-type 分叉。真缝隙另立 #405（标题带下滑仍收起）。建议按「不可复现」关闭（待用户确认）；局限=注入 swipe 非真手指采样。证据 docs/acceptance/2026-09-12-392-v1-sheet-fling.md。
  - 2026-09-12 后续（#405 定论）：V1 无服务器类型分叉已证；卡内所指「标题带」= 内容区 ≥538（标题文本实测 592 起），已受内容根手势块保护（ae52aeba 复验 PASS：600/670/700/541 VISIBLE）。建议 #392 按 NOT-REPRO 关闭。
  - 用户 2026-09-12 反馈：V1/V2 应共用同一套逻辑/容器，不该表现不同——要求深入排查（不接受仅「NOT-REPRO」结论）。
  - 2026-09-12 用户要求深查（不接受仅 NOT-REPRO）→ clean-context 侦查结论：整条链路（组件签名 / 抽屉几何 / 两道手势防线 / 入口门控 / jumpTargets 数据源 / 程序性 dismiss）无任何按服务器类型或服务器派生状态分叉的代码点；lint 白名单门禁（ServerTypeWhitelistDetector）从结构上禁止 Chat UI 引用 ServerType。差异最可能是**构建时间差**：#405 指针层兜底提交于 2026-09-12 04:18/04:41，SheetGestures 注释自证修复前标题带下滑必收起。唯一真实 V1/V2 输入差异 = 会话内容量（V1 靶会话 13 条 user）→ 抽屉内列表是否越过 75% 屏高可滚动边界，但两种情形两道防线均闭环。建议：核对用户两端 APK 构建 commit；必要时补 2×2 内容量矩阵复测。
  - 迁入依据：用户 2026-09-12 裁决：V1 复测通过即关闭。复测（模拟器 V1 4198，APK d05e712f）：列表快速 fling 上/下、标题带 600/670/700、内容上沿 545 全部 VISIBLE；手柄槽 522 与 scrim 收起正常；crash 0。V1 与 V2 几何一致、无 server-type 分叉，NOT-REPRO 定案。证据 docs/acceptance/2026-09-12-392-v1-recheck.md（backlog.sh migrate 2026-09-12）

### **#406 快速定位跳转后目标消息落在视口底部/下缘而非居中** `ui` `chat`
  - 2026-09-12 #405 抽屉复验观察（两次一致）：点快速定位条目后抽屉关闭、目标消息进入视口且高亮链正常（#394 已修），但落点在视口底部/下缘，阅读上下文需再滑一下。
  - 方向：评估 jumpTo 后对目标 turn 做 viewport 居中的可行性（LazyListState 的 offset 计算/动画），按「先调研再优化」纪律，登记待排期。
  - 用户 2026-09-12 反馈：记忆中跳转后目标消息是落在视窗顶端的，现在落到底部——要求排查是否为本次重构引入的回归。
  - 2026-09-12 clean-context 侦查结论：**不是 #391 重构回归，也不是顶对齐→底部的语义翻转**——成功跳转的终态至今=顶对齐（JumpNavigationController 渐进收敛，gap=目标顶边贴视口顶）。落底有两机制：① #394 修复前的 assistant 命中失败（u_ 前缀 vs t_ 键）→ 超时 Failed → 停在初始底部对齐位；窗口=08-31「进入即跳转」特性至 09-11 22:34 修复提交，最新 release v0.3.0(08-29) 无此路径；② 夹持收场（08-21 起，早于重构三周）——目标靠近列表最新端、下方内容不足一屏时顶对齐物理不可达，接受低位 Displayed。本地复验观察（高亮正常 + 两次落底）与②吻合。若要落点居中：把 computeGap / settled 判据 / 900ms 稳定窗口的顶对齐目标改为居中，其余机制不动；该文件是承重墙，须真机三态复验。请用户裁决是否实施居中。
  - 迁入依据：用户 2026-09-12 裁决：维持顶对齐（不做居中），并要求快速定位与关键词跳转点击两条路径都顶对齐。复测 PASS：两路径落点逐像素一致（用户头 333 / 正文 396，目标为视口第一项）；近列表末端夹持收场属物理不可达、设计内。证据 docs/acceptance/2026-09-12-406-jump-topalign.md（backlog.sh migrate 2026-09-12）

## 用户第三批：V1 复测结案 / 顶对齐复核 / 重连倒计时 / #407 flake 根治（2026-09-12）

- **#392 V1 复测 PASS 并结案**（用户裁决「复测没问题就关闭」）：列表快速 fling 上/下、标题带 600/670/700、内容上沿 545 全 VISIBLE；手柄槽 522 与 scrim 收起正常；crash 0。证据 `docs/acceptance/2026-09-12-392-v1-recheck.md`。
- **#406 维持顶对齐**（用户裁决不居中）：快速定位 Q1 与关键词 charlie 命中第 1 轮，两条路径落点逐像素一致（用户头 333 / 正文 396，目标为视口第一项）；近列表末端夹持收场属物理不可达、设计内。证据 `docs/acceptance/2026-09-12-406-jump-topalign.md`。
- **#409（新登记，由 #408 引出）断连横幅倒计时**：确认自动重连后，`SseConnectionManager` 新增 `reconnectAt` 排程（退避 `delay` 前经 `backoffWithSchedule` 登记 serverId→下次尝试 epochMs；连接成功 / `stopConnection` / `stopAllConnections` 清除），Chat/SessionList ViewModel 暴露 `serverReconnectAt`，横幅 `retryAtEpochMs` 非空时每秒 tick 显示「N 秒后重试」；i18n 新增键 ×15 语言通过（885 keys）。设备复验：停 V1 后三帧 1s/2s/1s 数值在变，V1 恢复后横幅消失，crash 0。证据 `docs/acceptance/2026-09-12-409-reconnect-countdown.md`。
- **#407 全量测试 flake 根因修复**：`RenderSupplyCoordinatorTest` 全程 `runBlocking` + 15s 墙钟 `withTimeout`，解析链硬编码共享 `Dispatchers.Default` → 全量跑时调度方差拖穿等待预算（基线 2/3 红；5s→15s 放大已被证伪）。修复=解析 dispatcher 可注入（`RenderReadiness.preParse` / `RenderSupplyCoordinator` 末位默认参数，**生产默认不变**），测试注入私有 daemon 单线程；隔离 `--tests` 绿 + 全量连续两次 `BUILD SUCCESSFUL`。伴发 `DraftInputDelegateTest` 为 runTest 默认 10s 墙钟（机制正交，未动）。
- **自身回归教训（已修复）**：ViewModel 新增 `stateIn` 的初值用 relaxed MockK 的 `reconnectAt.value[serverId]` → 返回 `Object` 无法转 Map，82 个 ViewModel 测试 `ClassCastException`；改为初值 `null` 后门禁全绿。

## 已完结卡片迁入（2026-09-12）

### **#391 ServerAdapter 服务器类型插件化架构（含 DSH 0.1.5/V3 适配）** `refactor` `dsh` `sse` `data`
  - 目标形态：ServerAdapter 聚合根 + @IntoSet 注册表 + 端口可选性派生能力 + 5 UI 插槽扩展 + ConnectionStrategy；新增服务器类型=新目录一个 bundle，零既有改动。
  - 现状根因：7 份手写路由、60 处 ServerType 特判、闭集能力矩阵+中心 switch、事件词汇无版本维度（DSH 0.1.5/V3 击穿历史与流式）。
  - 架构一次到位、开发按 8 切片分批（契约前两片冻结）；首个真实落地=DSH 0.1.5/V3 适配。
  - → docs/specs/2026-09-10-server-adapter-architecture-design.md
  - 通用 UIUX 统一纳入承重与交付：差异分级 L0/L1/L2 + 统一贡献注册表 + 隐藏/禁用判据；静态强制走 Android Lint 自定义规则（既有 lint 门禁，不新增工具链），审计矩阵 BAD 归零（切片 9）。
  - 开发切片数更新：9（原 8 + 通用 UIUX 统一落地，见 spec Further Notes）。
  - 进度（2026-09-10）：切片1-7 已实现并提交（3f59c5d9..8ce0107a）；切片8 上已提交 44095a9c（lint 门禁恢复绿 #396 清零 / 选择器注册表驱动 / 适配器契约测试 / 探测器去类型化 / 架构文档登记）；切片8 下的自定义 lint 规则见 #397；切片9（通用 UIUX 统一）未开工。
  - 2026-09-11 模拟器 E2E（代替真机）全量通过：A1-A3/B1-B3/C3/D1/D2/E1/E2 全 PASS、无 BLOCKED（证据 docs/acceptance/2026-09-12-391-400-emulator-evidence.md）。九切片代码 + E2E 就位，转待用户验收。
  - 迁入依据：用户 2026-09-12 授权：验收过的即可结卡。模拟器 E2E 全量 PASS（A1-A3/B1-B3/C3/D1/D2/E1/E2，无 BLOCKED）+ 九切片代码就位；证据 docs/acceptance/2026-09-12-391-400-emulator-evidence.md（backlog.sh migrate 2026-09-12）

### **#400 Testing seam 4：真机/模拟器 + 真实服务器端到端** `test` `dsh`
  - 现状：#391 批次全部验证为 JVM 单测/编译/lint；spec Testing seam 4（真实服务器 + 真机端到端）未执行。
  - 目标：模拟器或可达网络下跑 V1/V2/DSH 三面 E2E，重点覆盖历史拒绝重建（surfaceOp 越界）、assistant-stream 实时流、界面插槽渲染（横幅/设置区块）。
  - 阻塞：机场公共 WiFi 客户端隔离致无线调试不可达（10.3.2.3 ARP FAILED）；改用模拟器。
  - 进度（2026-09-11）：模拟器 OpenCode V2 面 E2E 通过——连接/会话列表/聊天转录/条目动作注册表（GOAL 按能力隐藏、QUEUE 在场）/SERVER_SETTINGS 槽位不泄漏 DSH 区块；无崩溃。
  - 未覆盖：DSH 线面（无靶机）——token 横幅、DSH 设置区块、surfaceOp 越界拒绝重建、assistant-stream 实时流。
  - DSH V012 面 E2E 通过（本机 DSH 3080 + debug_token）：token 交换 ok、wire=v012 authed、转录渲染、assistant-stream 实时流（t_dsh-t55s10 增量 RESIZE）、DSH FAB 能力过滤（Goal 有/Shells 无）、404 优雅降级、无崩溃。
  - OpenCode V2 面此前已过；V3 五类新事件渲染未覆盖（见 #398）。
  - 2026-09-11 模拟器代替真机全量复跑：A/B/C3/D1/D2/E1/E2 全 PASS、无 BLOCKED；C1 功能 PASS（指定会话因 V3 压缩边界未呈现）、C2 机制 PASS（字面片段在压缩边界外）。证据 docs/acceptance/2026-09-12-391-400-emulator-evidence.md。转待用户裁决 C1/C2 + 验收。
  - 迁入依据：同上授权。模拟器代替真机全量复跑全 PASS、C1 功能 PASS / C2 机制 PASS、DSH V012 面 E2E 通过；证据 docs/acceptance/2026-09-12-391-400-emulator-evidence.md（backlog.sh migrate 2026-09-12）

### **#399 切片9 剩余：条目动作贡献注册表 + 令牌门禁 + 审计矩阵 BAD 归零** `ui` `arch`
  - 现状：区域插槽（ServerUiSlot）已落并有三处贡献；但条目级动作仍是组件内联能力判断，非声明式贡献；令牌门禁（硬编码色值/间距/时长）未做。
  - 目标：#391 spec 切片9 统一落地——条目动作贡献注册表 + 令牌 Lint 规则接 :lint-checks + docs/research/2026-09-07-server-face-unification-audit.md 的 BAD 项（FAB 门控/队列空态泄漏）处置。
  - BAD 项核对（2026-09-11）：审计两条 BAD（FAB 五入口无能力位门控 / QUEUE 空态泄漏）已在统一审计批1（36734c23，2026-09-07）+ 本批切片2 能力位化后归零（ChatScreen.kt:1040-1046 entries 按 GOALS/TERMINAL/QUEUE 构建），审计文档未回标。
  - 因此本条剩余 = 声明式条目动作贡献注册表 + 令牌门禁（硬编码色值/间距/时长，接 :lint-checks，存量入 baseline）。
  - 进度（2026-09-11）：3 个 DSH 私有 UI 文件已迁入类型私有包（ui/components/dsh、ui/screens/server/providers/dsh、ui/screens/sessions/dsh），ServerTypeUiBoundary 豁免 6→3。
  - 剩余：ChatMessageList 的私有 DshJobTimelineCard、SessionListScreen 的 DshTokenDialog、SessionListViewModel 的 DshTokenExchangeState（需 token 对话框+交换状态下沉到 DSH 扩展）；86 文件标准间距存量迁移；声明式条目动作注册表。
  - 进度：DSH token 录入状态已下沉 DshTokenEntryViewModel（ServerTypeUiBoundary 豁免 6→1，仅余 ChatMessageList 私有 DshJobTimelineCard——需新增消息列表插槽，见本卡）。
  - 剩余：86 文件标准间距存量迁移（SpacingTokenBypass baseline）；声明式条目动作注册表。
  - 进度：86 文件标准间距迁移完成，SpacingTokenBypass baseline 清零。
  - 剩余：ServerTypeUiBoundary 最后 1 条（ChatMessageList 的 DshJobTimelineCard，需新增消息列表插槽）；声明式条目动作注册表。
  - 统一贡献注册表条目级部分已落：ServerActionContribution/ServerActionRegistry/LocalServerActions + ChatFabActionsModule 声明 5 条 FAB 入口贡献；ChatScreen 内联能力门控改注册表消费。区域插槽 + 条目动作两块齐。
  - 剩余：条目→内容映射仍由通用壳持有（设计如此）；V3 P1 渲染见 #398；E2E 见 #400。
  - 2026-09-12 收尾完成（a3dc93ce）：通用壳私有 DshJobTimelineCard 迁 CHAT_MESSAGE_LIST 插槽 + DshJobTimelineExtension；ChatViewModel 暴露 uiSlots 两级门禁；契约测试纳入新插槽。设备复验 PASS（3 张任务卡同参 EventCard 渲染、无错位、无崩溃）。证据 docs/acceptance/2026-09-12-399-slot-verification.md。转待用户验收。
  - 迁入依据：同上授权。CHAT_MESSAGE_LIST 插槽迁移完成（a3dc93ce）+ 设备复验 PASS（3 张任务卡同参渲染、无错位、crash 0）；证据 docs/acceptance/2026-09-12-399-slot-verification.md（backlog.sh migrate 2026-09-12）

### **#398 V3 新事件族渲染（切片7 P1）+ 按代事件词汇表** `dsh` `arch`
  - 现状：#391 两轴评审确认 V3 五类新事件（system/message、assistant/attempt、feedback/message-put|delete、subagent/catalog、deliverables/presented）仅 Ignored(SESSION_FORMAT_V3) 降级不渲染；事件映射仍是单体 when + protocolOf==V012 硬判。
  - 目标：按 spec 切片7 补渲染（系统节点/失败尝试/反馈/子智能体目录/产物卡）+ 落地按代 EventVocabulary 表。
  - 前提：需 DSH 0.1.5 真实 wire 样本（当前仅 0.1.1/0.1.2 实录），无样本不臆造字段。
  - 步骤1（2026-09-11，实况取证+修复）：V3 user/message 的 reasoning/tool-call 块此前被丢弃，现 reasoning→Part.Reasoning（对齐 assistant）、tool-call/result→静默（冗余镜像）；模拟器连本机 DSH 0.1.5-rc.1 实测 warning 16→0。
  - 本机 DSH 0.1.5 服务器（3080）已成为权威 wire 取证源；其余 V3 类型渲染 + 按代词汇表待续。
  - 步骤2（2026-09-11）：29 个 V3 归档取证固化到 docs/research/2026-09-11-dsh-v3-event-payloads.md——assistant/attempt(692)/system/message(41)/subagent/catalog(16)/deliverables/presented(15) 原始载荷+字段+映射计划；feedback/* 归档无样本。
  - 步骤3（2026-09-11）：system/message 已渲染为注入类精简卡（复用 injectionKind→EventCard）；assistant/attempt 判断修正为维持静默（681/692 后随 llm/retry，瞬态）；deliverables/presented 待接既有 TurnDeliverables 折叠面。
  - 2026-09-11 复核 + 模拟器 E2E：按代词汇表与 V3 五族处置全落，C3 容错 PASS。C1 产物 chip 功能 PASS（替代会话命中）、指定会话因 V3 压缩边界未呈现；C2 注入卡机制 PASS、字面片段在压缩边界外。转待用户裁决 C1/C2 判定。
  - 迁入依据：同上授权。按代词汇表与 V3 五族处置全落、模拟器 E2E C3 容错 PASS、C1 产物 chip 功能 PASS、C2 注入卡机制 PASS；证据 docs/acceptance/2026-09-12-391-400-emulator-evidence.md（backlog.sh migrate 2026-09-12）

### **#396 Android Lint devDebug 门禁 4 项存量错误** `lint` `ci`
  - 现象：./gradlew :app:lintDevDebug 红（abortOnError），4 error——HiltEntryActivity MissingClass ×1（src/debug/AndroidManifest.xml:18，类仅存在于 androidTest 源集）+ LocalContextGetResourceValueCall ×3（ChatScreen.kt:786/1067、SettingsScreen.kt:97 的 context.getString 应走 stringResource）。
  - 归因：blame 分别为 6c41d0a2(2026-08-16)/f2df106c/2e4a4d58/54cbc555(2026-08-31~09-01)，#106 批次曾清至 0 后回归；与 #391 切片1/2 无关（ChatScreen numstat 9/9 行数不变，命中行未改）。
  - 影响：#391 切片8 的自定义 Lint 规则要接入同一门禁，需先清此 4 项或确认 lintRelease 路径。
  - 2026-09-11 复核：4 项 devDebug lint 已在 44095a9c 清零（HiltEntryActivity 迁 src/debug + EventTimeString 收敛），:app:lintDevDebug 0 new issues。转待用户验收。
  - 迁入依据：同上授权。4 项 devDebug lint 已在 44095a9c 清零，:app:lintDevDebug 0 new issues（#391 切片8）（backlog.sh migrate 2026-09-12）

### **#394 跳转终点 5s 高亮未生效 + 疑似破坏会话渲染（优化2 复验未过）** `chat`
  - 用户复验（2026-09-10）：搜索命中行点击进会话后无 5s 高亮，且报告「似乎破坏会话渲染」。疑点：①Displayed 相位 hook 的 itemKey 键式推导（assistant 目标 t_ 键）与渲染 itemKey 实际格式不匹配→不设键不高亮；②async 跳转路径 entry 查空→静默不设键；③渲染破坏待复现取证（当前帧 /tmp/n5_regression.png VLM 复核健康——你好 会话轮次 19-21 气泡/台账正常，疑为 search-jump 进入路径暂时性）。实现：commit a0b24103。
  - 2026-09-12 修复（commit 99f6430f）：高亮 key 与 Lazy item key 统一 chatEntryKey（相位只记目标 msgId、键下沉渲染期推导，兼修异步查空）；新增 ChatEntryKeyTest 回归；待模拟器复验。
  - 2026-09-12 模拟器复验暴露真根因：initial-jump 对全部命中统一调 jumpTo（固定 u_ 前缀），assistant 的 Lazy key 是 t_<turn 首条 assistant>→永不匹配→超时 Failed、永不 Displayed。已修（86adacde）：新增 jumpToResolved 按角色分流 + turn 锚点；复验见 docs/acceptance/2026-09-12-fixes-394-393-387-verification.md。
  - 2026-09-12 修复后定向复验 PASS（APK 0cb9147e）：assistant 命中 jumpToResolved→Displayed→Highlight set key=t_…→clear（5.000s）；旧「布局稳定 超时/重定位 14 次」全消失；user 对照无回归；crash 0。证据 docs/acceptance/2026-09-12-fixes-394-393-387-verification.md 复验二节。转待用户验收。
  - 迁入依据：同上授权。修复后定向复验 PASS（APK 0cb9147e）：assistant 命中 Displayed + 5.000s 高亮、超时/重定位全消失、user 对照无回归、crash 0；证据 docs/acceptance/2026-09-12-fixes-394-393-387-verification.md（backlog.sh migrate 2026-09-12）

### **#393 搜索内容命中：全部过滤下 AI 行仍不可见（⑤复验未过——逐命中行改造后依旧）** `ui`
  - 用户复验（2026-09-10）：逐命中行+角色标签改造后，「全部」过滤下仍只见人类行。装机冒烟实证：story 查询首屏全用户行（BM25 短文档偏置 user 恒靠前），AI 行在折叠下方——待确认用户是否滚动；若 UX 需要 AI 无滚动可见，需按角色交错排序或每会话最优双角色先行。取证：拉库已证 FTS 层 AI 命中健全（whale: assistant 153 + user 14）。
  - 2026-09-12 修复（commit 99f6430f）：搜索内容命中组内按角色交错排序（users/agents 各自保持 rank 序，u,a,u,a…），AI 行不再沉到折叠区下方；待模拟器复验。
  - 2026-09-12 模拟器 clean-context 复验 PASS：词 test 首组「验收测试会话AB」首屏同时出现 用户 3 行 + 智能体 2 行（u,a,u,a 交替 = interleaveSearchHits）。证据 docs/acceptance/2026-09-12-fixes-394-393-387-verification.md；转待用户验收。
  - 迁入依据：同上授权。模拟器 clean-context 复验 PASS（词 test 首组 u,a,u,a 角色交错，AI 行首屏可见）；证据 docs/acceptance/2026-09-12-fixes-394-393-387-verification.md（backlog.sh migrate 2026-09-12）

### **#408 断连横幅出现时把原有内容顶推幅度远超横幅自身高度** `ui` `resilience`
  - 用户 2026-09-12 反馈（由 #401 引出）：断连横幅出现后，下方内容被顶推的高度远大于横幅条本身（正常应只等于横幅高度）。
  - 初判根因：横幅自身加了一次 statusBars inset padding，而其下方 TopAppBar 的 windowInsets 仍含状态栏 → 状态栏被计两次（横幅高度 + 2×状态栏）。待设备实测确认后根因修复。
  - 2026-09-12 根因确认 + 修复 + 设备复验 PASS：根因 = 横幅自身 statusBars padding（本机 128px）与其下方 TopAppBar 默认 windowInsets 各吃一次状态栏 inset。修复 = 新增 ZeroTopAppBarWindowInsets；ChatTopBar 增 windowInsets 参数；ChatScreen / SessionListScreen 在横幅可见时归零。实测内容顶推 195px → 67px（= 横幅条高 66px），Chat 与会话列表两面一致；服务器恢复后横幅消失、无「已恢复」提示。APK md5 d05e712f8510ef4e27c1530e76810fd8。证据 docs/acceptance/2026-09-12-408-banner-push.md。转待用户验收。
  - 迁入依据：同上授权。根因修复 + 设备复验 PASS（内容顶推 195px → 67px）；证据 docs/acceptance/2026-09-12-408-banner-push.md（backlog.sh migrate 2026-09-12）

### **#404 冷进入 DSH 会话后钉底任务卡/队列镜像暂缺——subscribed 空快照抹掉 control 基线** `dsh` `data`
  - session/subscribed 原发空 JobsSnapshot/QueueSnapshot 清空镜像，假设服务器随后重推整快照；本版 0.1.5-rc.1 控制流只在任务/队列变更时增量推送 → 冷进入会话 ≤30s 钉底任务卡缺失（等一次状态变化才出现）。
  - 根因修复：去掉 subscribed 清空，权威快照 = session/control baseline（每次 WS onOpen 重发、last-wins）；DshJobsStore/DshQueueStore 由基线+变更增量驱动。
  - → docs/acceptance/2026-09-12-399-slot-verification.md
  - 2026-09-12 根因修复（79f4f7f4）：去掉 session/subscribed 的空 JobsSnapshot/QueueSnapshot，权威快照=session/control baseline；两条 subscribed 断言同步更新；待设备冷进入复验（钉底卡应 ≤30s 立即可见）。
  - 2026-09-12 设备复验 PASS（APK 1a315b12）：冷进入会话 t+4s 即见钉底任务卡（sleep 1500/运行中），进入后 66s 内 0 次快照推送；修复前 ≤30s 无卡。证据 docs/acceptance/2026-09-12-402-404-verification.md。转待用户验收。
  - 迁入依据：同上授权。根因修复（79f4f7f4）+ 设备复验 PASS（冷进入 t+4s 钉底任务卡、66s 内 0 快照推送）；证据 docs/acceptance/2026-09-12-402-404-verification.md（backlog.sh migrate 2026-09-12）

### **#402 SSE 5min 冷却致非网络切换型瞬断恢复迟滞——隧道/服务端瞬断实测 ~4m38s 才重连** `sse` `resilience`
  - SseConnectionManager 冷却期内 runSseConnectionLoop 只 delay(30s) 不发起连接，仅 Android 网络恢复回调 reconnectServer 会 reset；adb reverse 恢复不触发网络事件 → 等满冷却。
  - 实测：reverse 回加后 HTTP 已 200，但 Connected 迟至 ~4m38s；断连条幅期间用户无手动重连入口（#267 零交互裁决）。
  - → docs/acceptance/2026-09-12-390-disconnect-repro.md
  - 2026-09-12 根因修复：冷却语义专指读超时（withTimeoutOrNull→null→break→流正常结束路径计数），异常分支的连接级快速失败（refused/DNS/HTTP 非 2xx）不再计冷却、改由 calculateBackoff 指数退避；待设备复验恢复时延。
  - 2026-09-12 根因修复（79f4f7f4）：异常分支不再计冷却，连接级失败走 calculateBackoff；待设备复验（reverse 恢复后重连应 ≤60s）。
  - 2026-09-12 设备复验 PASS：连接级失败 0 冷却、1000→2000→4000ms 指数退避；reverse 恢复后 ~2.0s 重连（旧 ~4m38s）。证据 docs/acceptance/2026-09-12-402-404-verification.md。转待用户验收。
  - 迁入依据：同上授权。根因修复（79f4f7f4）+ 设备复验 PASS（0 冷却、1/2/4s 退避、reverse 恢复后 ~2.0s 重连）；证据 docs/acceptance/2026-09-12-402-404-verification.md（backlog.sh migrate 2026-09-12）

### **#397 自定义 Android Lint 规则（服务器类型分支白名单 / 界面分层 / 令牌绕过）** `lint` `arch`
  - 背景：#391 切片8 的静态强制部分未落——spec 要求走自定义 Android Lint 规则，但 Lint 检查必须是独立 Gradle 模块，与 spec Out of Scope「不把单模块拆成多 Gradle 模块」存在取舍，需用户裁决。
  - 现状：本机 Gradle 缓存已具备 lint-api/lint-checks 32.3.2（与 AGP 9.3.2 匹配），可离线新增 :lint-checks 模块（com.android.lint 插件 + Detector + IssueRegistry + META-INF services）+ app 端 lintChecks(project(:lint-checks))。建议先只落「ServerType 分支白名单」一条（文本级 Detector，白名单：类型定义/ServerConfig/ServerConnection/data-adapter/ServerDialog/调试入口），跑通后再扩 UI 分层与令牌两条（存量需入 baseline）。
  - 替代方案：脚本门禁（grep）复用现有 release 门禁，零新模块但表达力弱。当前兜底：架构文档承重规则 + code review。
  - 进度（2026-09-11）：:lint-checks 模块已落并接入 app 的 lintChecks；首条规则 ServerTypeWhitelist（剥离注释后判 ServerType 词元 + 路径白名单）经探针实证可拦截、当前树 lintDevDebug 绿。
  - 剩余「通用界面 import 具体类型组件」「令牌绕过（硬编码色值/间距/时长）」两条未落。
  - 白名单已收窄到文件级精确清单（ServerTypeWhitelist 含 MainActivity/DebugProfile/domain-model 三件/server-adapter/存储/重复后端比较/用户选择四件）；architecture.md 同步点名。
  - 剩余两条规则未落：通用界面 import 具体服务器类型组件 / 令牌绕过（硬编码色值·间距·时长）。
  - 第二条规则 ServerTypeUiBoundary 已落（通用界面禁 import/声明 Dsh/OpenCode 前缀 UI 符号，排除 /dsh/、/opencode/、ui/theme/）；6 处存量经 baseline 豁免，lintDevDebug 0 new issues、lintDevRelease 绿。
  - 仅剩令牌门禁：分析见 journal——建议窄化为「非 theme 的 Color(0x…) + 动画时长 tween/durationMillis」，间距（令牌值 .dp 372 处/padding 193 处）因 ui-conventions 允许 dp 常量，需独立迁移批次。
  - 第三条规则 TokenBypass 已落（色值字面量 + 动画时长 tween/durationMillis，排除 theme 与分类调色板）；唯一存量 CopyButton 已真实改用 AppMotion 令牌（新增 FAST=100），未入 baseline。spec 四条静态强制仅剩「dp 间距」因 ui-conventions 允许多为约定内写法，另立迁移批次。
  - 第四条规则 SpacingTokenBypass 已落（padding/spacedBy/PaddingValues 的标准间距值 4/8/12/16/24/32.dp），86 处存量入 baseline。至此 spec 四条静态强制全部落地。
  - 剩余：86 文件间距存量迁移 + 6 处类型私有 UI 迁移（见 #399）。
  - SpacingTokenBypass 存量已清零：86 文件标准间距机械迁移到 SpacingTokens（逐值等价），baseline 减 602 行。四条静态规则全部零存量（boundary 仅余 1 条消息列表私有卡）。
  - 四条静态规则零存量 error：ServerTypeUiBoundary 收窄到非 private 声明后最后 1 条豁免清除（lintDevDebug 0 error）。
  - 2026-09-11 复核：:lint-checks 四规则接入 lintChecks、四规则 baseline 0 条，:app:lintDevDebug 0 new issues。转待用户验收。
  - 迁入依据：同上授权。:lint-checks 四规则接入 lintChecks、baseline 0 条、:app:lintDevDebug 0 new issues（backlog.sh migrate 2026-09-12）

### **#395 立即发送（steer）上屏消息缺标识徽标——chat_queued 徽章链整撤连带丢失** `queue`
  - 用户裁决（2026-09-10）：排队不上屏 ✓ + 立刻发送（steer）上屏 ✓，但 steer 消息上屏后无任何徽标标识是有问题的——需恢复徽标（建议 steer 专属文案如「插话/注入中」而非「排队中」，文案待用户裁决；i18n ×15 + MessageCardUser 两变体渲染点）。注意 steer 无 wire 侧标记——识别依赖发送路径（steer=true 时 seedTranscript 播种），徽章状态需随消息携带或按 rpcId 关联。
  - 2026-09-12 实现+两轮模拟器复验 PASS：忙碌长按 steer 徽标「插话」持续 ≥45s（越过 L3 REST 兜底刷新），重进会话仍在，Room payload 含 viaSteer:true；负向对照无徽标、crash 0。实现 605d2194 + 持久化修复 d9a79767；证据 docs/acceptance/2026-09-12-395-steer-badge-verification.md。文案 zh=插话（待你最终裁决可调）。转待用户验收。
  - 迁入依据：同上授权（文案「插话」用户已 ok）。两轮模拟器复验 PASS（徽标持续 ≥45s、重进会话仍在、负向无徽标、crash 0）；证据 docs/acceptance/2026-09-12-395-steer-badge-verification.md（backlog.sh migrate 2026-09-12）

### **#403 DSH system/message 走 role==system 分支遮蔽 injectionKind——标签恒「工具目录已变更」，与 #398「复用 injectionKind→EventCard」不符** `ui` `dsh`
  - ChatMessageList role=="system" 分支（L1664）先于 injectionKind 分支（L1712）；DB 中 system 消息 payload 带 injectionKind=plugin，但 UI 标签恒 chat_event_tool_catalog_changed。 -s 实测（会话 a84edbf7）：展开 system 注入卡字面可见，但标签非 kind 派生；「插件配置/上下文注入」标签只出现在 user/message+source.kind 路径。
  - → docs/acceptance/2026-09-12-400-c2-literal-verification.md
  - 2026-09-12 修复+模拟器复验 PASS（e6a4a3e3）：首张系统注入卡标签由固定「工具目录已变更」变「插件配置」（injectionKind=plugin 生效），展开正文仍含 harness 字面、crash 0。证据 docs/acceptance/2026-09-12-403-label-verification.md。转待用户验收。
  - 迁入依据：同上授权。修复（e6a4a3e3）+ 模拟器复验 PASS（标签由固定「工具目录已变更」变「插件配置」、展开正文仍含 harness 字面、crash 0）；证据 docs/acceptance/2026-09-12-403-label-verification.md（backlog.sh migrate 2026-09-12）

### **#390 服务器断连时会话页空白/弹回服务器管理无重连提示** `resilience`
  - 今日链路闪断窗口多帧实证（VLM 确认仅剩状态栏）：reverse 隧道拆→app 断连→会话数据释放（EventDispatcher releaseSessionData）→转录空白或弹回服务器管理界面，期间无重连横幅/按钮，用户无路可走。需断连 UX 兜底（提示+重连入口），证据链 journal 2026-09-09-378-380-wire.md §十五
  - 2026-09-12 模拟器 clean-context 复现核查：三处指控（无横幅/转录空白/弹回管理页）均不成立——拆隧 ~5.5s 出现「服务器已断开，正在重连…」条幅且 19/19 帧在、转录 19 帧非空且无 releaseSessionData、90s 无导航；#267 已覆盖，建议关闭（待用户拍板）。残余缺口另立 #401（Home 无条幅）/#402（5min 冷却恢复迟滞）。证据 docs/acceptance/2026-09-12-390-disconnect-repro.md。
  - 迁入依据：同上授权。clean-context 复现核查：三处指控均不成立（条幅 19/19 帧、转录非空、90s 无导航），#267 已覆盖；证据 docs/acceptance/2026-09-12-390-disconnect-repro.md（backlog.sh migrate 2026-09-12）

### **#409 断连横幅显示下次重连倒计时** `ui` `resilience`
  - 用户 2026-09-12 反馈（由 #408 引出）：确认自动重连后，希望在断连横幅上显示「N 秒后重试」倒计时（现在只有常驻文字）。
  - 实现要点：SseConnectionManager 退避 delay 前登记 serverId → 下次尝试墙钟时间（reconnectAt StateFlow），ViewModel 暴露，横幅每秒 tick 计算剩余秒；文案 i18n ×15。
  - 2026-09-12 实现 + 设备复验 PASS：SseConnectionManager 新增 reconnectAt 排程（每次退避 delay 前登记 serverId→下次尝试 epochMs，连接成功/连接销毁/全停清除），Chat/SessionList ViewModel 暴露 serverReconnectAt，横幅 retryAtEpochMs 非空时每秒 tick 显示「N 秒后重试」；i18n 新增键 ×15 语言通过。实测停 V1 后三帧 1s/2s/1s 数值在变，V1 恢复后横幅消失，crash 0。APK md5 e84868574c2df04240808ba3dd9cfd7b。证据 docs/acceptance/2026-09-12-409-reconnect-countdown.md。转待用户验收。
  - 迁入依据：同上授权。实现 + 设备复验 PASS（1s/2s/1s 倒计时实时刷新、恢复后横幅消失、crash 0）；证据 docs/acceptance/2026-09-12-409-reconnect-countdown.md（backlog.sh migrate 2026-09-12）

### **#407 RenderSupplyCoordinatorTest 全量族跑间歇超时（T8/T11）** `test`
  - 2026-09-12 三次全量 :app:testDevDebugUnitTest 中两次出现 T8/T11 （T11 后再现 T8），**单独 --tests '*RenderSupplyCoordinatorTest' 重跑恒绿**；同次全量里 DraftInputDelegateTest 也偶发 1 次、隔离即绿。
  - 影响：全量测试门禁随机红，需人工二次判定；方向=核实 T8/T11 的 withTimeout 余量是否受同 JVM 并发/首次类加载拖慢，或改用虚拟时钟/放宽超时；属测试基建（非产品缺陷），登记待排期。
  - 补正：上条第二处被 shell 反引号吞字——失败类型为 TimeoutCancellationException（kotlinx.coroutines，CoroutineDebugging.kt / Timeout.kt），出现在 RenderSupplyCoordinatorTest T8/T11。
  - 2026-09-12 根因修复 + 验证 ：诊断结论=该类全程 runBlocking + 15s 墙钟 withTimeout，解析链硬编码共享 Dispatchers.Default → 全量跑时调度方差（邻居泄漏/机器争用/GC 风暴）拖穿等待预算；5s→15s 放大已被证伪（2/3 仍红）。修复=给解析链注入 dispatcher（RenderReadiness.preParse 新增 parseDispatcher=Default；RenderSupplyCoordinator 末位同名默认参数——生产行为零变化），测试 Env 传私有 daemon 单线程执行器。验证：隔离 --tests 绿；全量 :app:testDevDebugUnitTest --rerun 连续两次 BUILD SUCCESSFUL（基线 2/3 红）。伴发 DraftInputDelegateTest 为 runTest 默认 10s 墙钟，机制正交未动。转待用户验收。
  - 迁入依据：同上授权。根因修复（解析链 dispatcher 注入，生产默认不变）+ 全量 :app:testDevDebugUnitTest --rerun 连续两次 BUILD SUCCESSFUL（基线 2/3 红）（backlog.sh migrate 2026-09-12）

### **#401 服务器管理（Home）界面缺断连条幅——#267 双界面范围外的第三面** `ui` `resilience`
  - 断连态下 Home 的服务器卡片仅「正在连接…」+「取消」，无 ServerLinkBanner；#267 只覆盖 Chat/会话列表两界面（Home 不在其声明范围，非回归）。
  - 实测：reverse 拆隧后 Chat/会话列表/设置 tab 均有条幅（≈5.5s 首现）；Home 无（step2-home.xml）。
  - → docs/acceptance/2026-09-12-390-disconnect-repro.md
  - 2026-09-12 调研前置受阻：web_search 端点 402 Insufficient Balance、M3 文档 JS 渲染 fetch 无正文；按用户纪律（UIUX 先全网调研）不擅动，待检索恢复后调研再优化。
  - 2026-09-12 调研完成（web_search 仍 402，降级 curl 直连一手源：androidx 源码 + Google 官方示例 + M3 页面 meta）：M3/commonMain 无常驻条幅组件；Now in Android 对离线用 duration=Indefinite 常驻 snackbar；本仓库既有 ServerLinkBanner（#267）已用于 Chat/会话列表。建议方案=仅当活动服务器非 Connected 时在 Home 顶部条件渲染既有 ServerLinkBanner（复用不新增组件）；冗余风险待用户拍板。详见 docs/research/2026-09-12-ux-research-405-401.md。
  - 用户 2026-09-12 裁决：Home 面确实不需要断连条幅（原范围终结，本卡据此结案）。同一反馈引出新缺陷——横幅出现后把原有内容顶推的幅度远超横幅自身高度，另立 #408 跟踪根因修复。
  - 迁入依据：用户 2026-09-12 裁决：Home 面确实不需要断连条幅（原范围终结）；同一反馈引出的顶推缺陷另立 #408 且已修复结卡（backlog.sh migrate 2026-09-12）
