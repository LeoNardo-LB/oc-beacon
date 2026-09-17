# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#415**（2026-09-18 #414 RenderSupplyCoordinatorT）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。**反馈归卡（2026-09-12 用户定规）**：用户对某张卡片的反馈/裁决一律经 `backlog.sh note <N>` 记入**该卡片**明细，**不另开新卡**承载反馈；仅当反馈引出**新的独立缺陷**时才另立卡片，并在两卡明细互相引用（#401→#408 为先例）。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |
| **P4** | **外部前提阻塞**：功能/工作方向明确，但实现前提在 app 之外（服务器能力缺失 / 上游未合 / 用户流程门槛），前提满足前不可动工——**卡内必含「前提」行**（前提是什么、现在为何做不了）；前提变化时重验归位 | 服务器未暴露的事件聚合、上游 PR 候选清单 |

**修复方针**（2026-09-03 用户定规）：bug 类条目**根因修复优先**——交付的修复必须消除触发链的根因层（架构 / 生命周期 / 状态机 / 协议缺陷），不以表象层兜底单独交差（「缓存兜底显示」「重试遮罩」「吞异常」等手段不得作为修复本体）。兜底类缓解仅在同时满足以下条件时接受：①对应根因修复卡已登记并被引用；②兜底卡摘要显式标注「过渡措施」并关联根因卡编号。分层不明确或拿不准时，先向用户呈现根因分析与分层方案，裁决后再落卡/动工。

**验证方针**（2026-09-03 用户定规）：**绝大多数情况（含 UIUX/交互类改动）都应通过真机端到端测试验证并关闭**，`[~]` 不按「是否 UIUX」划分，按「是否存在可注入/可观测的自动化仪器」划分。可用仪器（持续积累）：uiautomator dump/tap（导航+断言，注意 LazyColumn 视口冻结——dump 前先滚顶）、logcat/Room 日志直查、`/proc/net` socket 观测、debug 注入工具（#305 `--ez debug_simulate_timeout` 先例）、curl 模拟服务器侧事件（POST 建会话/订阅 SSE 抓帧）、网络扰动（nc 黑洞 + `adb reverse` 重定向 + `adb kill-server` 断既有连接）、`pm install` 静默装包 + `debug-entry.sh` 确定起点。**仅以下情形才需要人工介入**：①真手指连续手势/体感类——注入手势是平台批处理伪影，无法模拟真手指位移流（#245 两轮证伪）；②需用户凭据/跨设备操作（GitHub 密码/2FA、扫码等）；③数日级真实使用观察（不可加速，如挂机断链观察）；④主观体验拍板类（「好不好用」的最终确认）。判定次序：先穷举仪器→构造红回路→E2E 验证关闭；真找不到仪器才登记人工项并写明卡内为何仪器不可行。

**状态流转**：代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才算完结；完结即迁移（见首段）。

| 状态 | checkbox | 含义与流转规则 |
|------|----------|------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。后续 Agent 看到 `[~]`：向用户给出验证清单并请其执行；通过 → 转「已完成」并**当场迁移**；发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 仅迁移瞬间存在的过渡态——迁移完成后本文件不含任何 `[x]` 顶层条目（check 脚本强制） |

**Tag 标签体系**：标记相关领域便于批量排查；现有 Tag 不足以描述则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |
| `security` | 安全与隐私（明文凭据、泄漏、合规） |
| `refactor` | 重构、死代码清理、分层修复 |

**Spec**：满足「有非显然取舍需留档」或「跨会话实现需完整上下文」其一 → 在 `docs/specs/` 写 `YYYY-MM-DD-<名称>-design.md`（spec 是权威，卡片只留摘要+链接）；实现并用户验收后移入 `docs/archive/specs/`，同步更新 spec 头部状态行与卡片引用路径。**归档 spec 定期清理零外部引用者**（git history 永久可找回）。简单需求不写 spec。

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。**新 journal 术语三原则**：①叙述段用 CONTEXT.md 规范名；②证据引用豁免（logcat 行、SSE 事件名、i18n key、标识符原样保留）；③规范名首现带英文原词，编号遵循 [numbering-charter](docs/numbering-charter.md)。

---

## P0 — 主流程阻塞

## P1 — 核心功能需求

## P2 — 优化与锦上添花

- [ ] **#412 androidTest 长期不可运行：Compose 常驻帧泵致 idle 超时 + Room 迁移缺失（预存在）** `test` `infra` `dsh`
  - 2026-09-17 修复 Hilt 测试图缺口（build.gradle.kts 补 kspAndroidTest(hilt-compiler)；FakeDomainModule 补 ServerSettingsRepository 绑定 + FakeServerSettingsRepository）后，connectedDevDebugAndroidTest 首次真正运行：113 tests / 25 failures。20 例为 androidx.compose.ui.test ComposeNotIdleException（Idling resource timed out）。
  - 根因：ChatMessageList.kt 常驻 LaunchedEffect { while(true){ withFrameNanos{}; PreRenderShiftChannel.drain(listState) } }（#258 渲染前补偿帧界排空泵）——任何渲染 ChatScreen 的 Compose 测试永不 idle。另：SampleInstrumentedTest 等有 1 例 IllegalStateException（Room 迁移 1→9 缺失）+ 1 例 AssertionError。
  - 修法（需谨慎，属 SSE 铁律域）：给 PreRenderShiftChannel 增加「待排空信号」（Compose MutableState 计数器 + snapshotFlow，或 Channel）使泵仅在有待注入时起帧，空闲时挂起 → 测试可 idle 且不改变帧时序语义；或为测试提供 Local 关闭泵。需真机/模拟器复核流式滚动三铁律不回归。Hilt 部分已修（2026-09-17）。

- [~] **#387 V2注入刷新消息渲染为用户气泡文字墙** `chat` `ui` `v2`
  - skill-catalog/上下文刷新类注入（<system-reminder>包裹、无source.kind标记）按普通用户气泡整文渲染，[Ack] 3 会话顶部现存活例（VLM 09-41 复核：calculator 全文蓝色气泡墙，而同位插件配置已是收起小卡）。初判服务端对此类刷新不带 kind，mapper 按普通 user 落库。根因方向：对齐 dsh web 对 system-reminder 注入的识别与收起呈现（内容嗅探或等价机制），修在映射/渲染层单点。证据：/tmp/n2_acklink_top.png n2_ackthree_top.png；演示批 journal 待补
  - 2026-09-12 修复（commit 99f6430f）：新增 domain 纯判定 SystemInjection.isPureReminder + 渲染单点嗅探，无 source.kind 的 <system-reminder> 闭合块走既有折叠卡（混合消息不折叠）；新增 SystemInjectionTest；待模拟器复验。
  - 2026-09-12 模拟器复验 NOT-REPRODUCIBLE：DB 中纯 <system-reminder> 的 user 消息 10 条但 10/10 带 source.kind（DSH 路径已折叠），无 kind 样本 0 条。已落 defensive 渲染层嗅探 + SystemInjection 单测；无 live 样本，请裁决是否关闭/保留观察。
  - 2026-09-12 V2 宿主侧复验：/api/session/{id}/instructions/entries 对新旧会话均为空；抓 /api/event 90s 新会话无注入帧 → 本环境无 live 样本（与模拟器 DB 扫描结论一致）。defensive 嗅探+SystemInjectionTest 保留；建议按「无 live 复现」关闭或保留观察，待你裁决。
  - 用户 2026-09-12 裁决：参照 dsh web / opencode web 对 system-reminder（注入/上下文刷新）的识别与收起逻辑，仿照其逻辑重构或开发客户端渲染。
  - 2026-09-12 按用户裁决调研 dsh web / opencode web 后实现：两端均**不用内容嗅探**——dsh 靠 user/message 的 source.kind（≠user 即折叠为 context 节点，dsh client.js:6048-6066），opencode 靠 text part 的 synthetic 字段（synthetic part 在用户气泡隐藏，message-part.tsx:1198-1200；生产端 reminders.ts:26-48）。我们的 V2 服务器两类字段都不发 → 「字段优先 + 嗅探兜底」是唯一可行路线。本次改动 = 嗅探下沉到映射单点 DshEventMapper.mapUserMessage（无 source.kind 且整条恰为一个闭合 system-reminder 块 → injectionKind=context），实况/通知/未来消费者共用；渲染层对历史 Room 行的同判据（SystemInjection.isPureReminder）兜底保留，新增单测（纯块→context、混合→null）。后续方向（登记在卡内、不另开卡）：① dsh form 结构化展开体；② opencode synthetic 式 part 级混合拆分。局限：本环境无 live 无字段样本，该路径仅单测覆盖。
  - 用户 2026-09-12 新考虑（本卡保持打开）：是否把 agent 的内容直接输出、不再用 agent 气泡包裹，以及这种形式是否应由 dsh（服务器字段/模型）来驱动。待用户明确指代对象（上下文注入 / 子智能体输出 / assistant 正文）后再定方案。已有调研事实：dsh web 对注入不是裸输出而是折叠成 Context injection/recall 行（标题+来源标签+可展开体，client.js:850-898）；opencode 对 synthetic part 是直接隐藏、工具上下文折叠成 Gathered context 组——两端都不裸输出。
  - 2026-09-12 后续专题底稿已建：docs/research/2026-09-12-387-followup-discussion.md（已落地最小修复 + dsh/opencode 一手事实 + A/B/C 指代 + 候选方案与验证矩阵）。本卡转「专题讨论待定」，结论出来后按 spec 约定另立 spec/卡。
  - 2026-09-12 专题结论（6 轮 grilling）：设计定稿并发布为 GitHub Issue #11（标签 ready-for-agent）——消息层改扁平三段式（去气泡外观、保留头部/正文/尾部骨架）；通知层统一为通知卡家族；重指标与逐轮明细收进顶部统计弹窗（改底部可滚动面板）；撤销 UI 层 ×N 合并、改数据层按事件身份键原位更新。spec: docs/specs/2026-09-12-message-chrome-flattening-design.md；字段盘点: docs/research/2026-09-12-message-chrome-field-inventory.md。本卡后续按 #11 跟踪。
  - - 2026-09-17 按 spec/Issue #11 实施：批1 数据层（DSH 模型路由 + usage 全桶 + agent 漂移清理 + 行模型 seam）与批2 消息层（扁平三段式去容器 + 状态徽标 + 「更多」面板 + 尾部吸收台账/产出 + 间距 16dp + 身份键去重）已提交；批3 统计弹窗实施中。journal: docs/journal/2026-09-17-387.md。
  - - 2026-09-17 实现完成（批1/批2/批3 + i18n），V1 门禁全绿（compile / 3337 单测 0 fail / androidTest 编译 / assembleDevDebug / lintDevDebug 0 error）；V3 模拟器走查通过（用户消息扁平、assistant 三段式、通知卡同宽、统计底部面板 + 逐轮明细），证据 docs/acceptance/2026-09-17-387/。详见 journal docs/journal/2026-09-17-387.md。局限：Maestro CLI 未安装（V2 flow 未执行）。等用户验收。
  - - 2026-09-17 用户裁决修订：**用户消息保留原三段式气泡**（否决「去底色」）；扁平化范围收窄为智能体正文。spec/研究底稿/kdoc/Issue #11 已同步。

## P3 — 观察与低价值改进

- [ ] **#414 RenderSupplyCoordinatorTest T11 单测偶发失败（flake）** `testing`
  - 现象：全量单测首轮偶发 RenderSupplyCoordinatorTest > T11_文本增长后重析并覆盖已提交的陈旧plan FAILED；单测隔离重跑与全量重跑均通过。
  - 2026-09-17 #387 v2 追加批次取证（同一批次内一次失败一次通过）。方向：排查协程时序/共享状态，必要时加 awaitIdle；与 UI 改动无关。

- [ ] **#413 聊天页右下任务 FAB 遮挡列表末条消息尾部动作** `chat-ui`
  - 输入区右下浮动任务按钮（content-desc 打开任务菜单）压住最后一条用户消息尾部的 ⓘ/复制区域，其余消息无遮挡。
  - 方向：列表 contentPadding 预留 FAB 高度，或 FAB 与列表末项避让；属既有叠加布局问题，非 #387 v2 引入（2026-09-17 模拟器走查发现）。

- [ ] **#411 DSH 逐轮 TTFT / tokens·s 数据源接入（统计弹窗逐轮展开）** `dsh` `ui`
  - #387 spec US#26 部分实现：逐轮明细展开体的 TTFT / 解码速度当前恒 null（ContextDetailDelegate 无逐轮源，能力位门控正确隐藏）。dsh web 有 per-turn ttftMs / tokensPerSecond（节点 timing.firstTokenTime/stepStartTime 派生，盘点 §4.2）；app 尚未消费 DSH per-step timing。修法：DSH 事件侧持久化 step timing → TurnDetailInput.ttftMs/tokensPerSecond。

- [ ] **#410 助手消息尾部统计栏单点抽取（消 AssistantTurnTail 与 ChunkStatsBar 同构）** `refactor` `ui`
  - #387 双轴评审 S1：主路径尾部（MessageCardAssistant statsBar）与 ChunkStatsBar 约 120 行同构（模型/耗时/轮号/步数工具摘要/tailExpanded 状态机/复制/分支/更多/MoreSheet 装配/ProducedFilesRow 全部成对重复）；turnNumber/onForkFromTurn/onDeleteMessage 三参数贯穿 5 层 composable（Data Clump）。修法：抽单一 AssistantTurnTail，参数打包；ChunkStatsBar 更名。
  - 风险：layout scope（RowScope vs ColumnScope）迁移需模拟器复核流式/分片两态。

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配

- [ ] **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
