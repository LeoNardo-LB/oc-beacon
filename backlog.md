# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。

**编号**：全局递增，不回收。下一编号：**#186**。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |

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

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。

---

## P0 — 主流程阻塞

> 架构评审批次（2026-08-21，用户定 P0）：六候选 + 顺手清理，证据与设计定案全在 journal。#169 已完结（用户验收 2026-08-21，归档 journal），当前推进 #170。

- [~] **#170 架构评审候选 2：连接生命周期协调器 ConnectionLifecycleCoordinator——已实现，待用户真机验收** `refactor`
  - 三段式落地（d3baf95c/b297e47e/d21a45f5）：connect 七步/disconnect 四路单点化，双份 teardown 合一；registry 真相源 + FGS 回调派生；10 条 JVM 测试 + 全量单测过（1 例无关 flaky 已记录）
  - 真机 E2E 四场景过：连接（幂等真实触发）/断开/重连/飞行模式恢复（SSE 自愈）；待用户验收 UI 状态观感（维度 5）
  - → `docs/journal/2026-08-21-arch-review-deepening.md` · `CONTEXT.md`

- [~] **#171 架构评审候选 3：未读红点时钟域收进 interface——已实现，真机 E2E 全绿，待用户验收** `refactor` `data`
  - 三段式（a048b1ea/2231d301/941f17f8/a33d0d27）：UnreadEvent 事件化封死客户端时钟域泄漏（漏斗载荷提取 + DB 回环 seedCachedMessages 隔离）；已读侧全吸收（Signal 删除/判定入模块）；1808 单测 + 真机红点四态+双持久化全绿
  - ⏳ 维度 5（红点观感）待验收；错误红点真机无触发手段（JVM 覆盖）
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [ ] **#172 架构评审候选 4：V1/V2 seam 按域翻转（79 决策点 → 7+1）** `refactor`
  - 每域一 interface、V1/V2 各一 adapter，版本连接时选定一次；isV2 从 SessionStateService/分页用例收回 adapter 内
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [ ] **#173 架构评审候选 5：ChatViewModel delegate 按状态簇重组（消灭假 seam）** `refactor` `ui`
  - UI 消费 98 成员 + delegate 间 sink 回写/lambda 互接——按状态簇重组为 3-4 个所有权完整 module；排序在 #169 之后
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [~] **#174 架构评审候选 6：SessionStateService 8 回调旋钮 → 1 必需协作者——已实现，真机烟雾全绿，待用户验收** `refactor`
  - f179ad70+ab2c36c3：SessionStateCollaborator 构造注入（漏接=编译错误），EventDispatcher 接线块迁入 Impl，1808 单测全绿；真机 FSM 完整生命周期经新接线实证（含 force-complete×2）
  - ⏳ 维度 5（FSM 状态 UI 观感）待验收；僵尸场景（3min busy）JVM 覆盖
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [~] **#175 架构评审顺手清理四件 + bonus——已实现，真机烟雾全绿，待用户验收** `refactor`
  - 四件全落地（65a51723/67d496f3/276f2850/d757d499）：双调用点合一（子会话 else 分支真机实证）· 删三壳（Boolean 签名保留+契约测试）· 双胞胎合并 · ScrollPositionDelegate 死代码删除；bonus：repo deprecated trio 三层删除
  - 全量 1805/1805 绿（-3 死代码测试 +2 契约测试）；→ `docs/journal/2026-08-21-arch-review-deepening.md`

## P1 — 核心功能需求

- [ ] **#155 会话内提示音：被抑制的系统通知转为提示音+震动，严格镜像系统通知策略** `ui` `sse`
  - 前台会话 turn 结束/权限/问题/错误事件现状零反馈 → 补提示音+震动，策略完全镜像系统通知四层静音矩阵；错误 streak 只响第一声；零新增设置项（含 VIBRATE 权限与通知侧 streak 去重）
  - spec 已定案（grilling Q1–Q12 + F1–F5），实现前必读；模拟器无音频输出，维度 5 必须真机实测
  - → `docs/specs/2026-08-21-in-session-audio-feedback-design.md` · `docs/journal/2026-08-21-in-session-audio-feedback.md`

- [ ] **#151 错误日志 GitHub 上报（手动触发 + 指纹查重 + 重复评论）** `ui` `data`
  - Diagnostics 屏把 ERROR/FATAL 报到 GitHub issue；已报过的追加环境差异评论；强制预览可编辑；GitHub App device flow 一次授权永久有效
  - 前置依赖：#152、#153、维护者注册 GitHub App（spec §Further Notes 操作清单）
  - → `docs/specs/2026-08-21-error-report-github-design.md` · `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#152 前置：日志分级修复（SSE 灌水/双日志/丢堆栈，审计 15+ 处）** `sse` `refactor`
  - 重连风暴灌水（挤出真实错误）、同一失败双日志、per-event INFO 遗漏网、7 处 `e` 缺 throwable 等——不修则 #151 的"最近 20 条错误"全是重连噪音
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#153 前置：release CI 留存 R8 mapping.txt artifact** `refactor`
  - release.yml 只传 APK，mapping.txt 随 runner 销毁——用户混淆堆栈永久无法还原；加 artifact 上传（90 天保留）
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - spec §Out of Scope 明确后置项；触发条件：#151 落地并稳定后评估
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`

- [~] **#150 V1 连接速度慢于 beta.4 误判 V2——探测复用 + 预加载/SSE 并行化** `perf` `v1`
  - 已实现并合回 master（25927de5）：V1 冷首连 ~3×（81-138ms→25-43ms），模拟器 E2E 5 项全过（含升级场景真机复现）
  - 剩余：真机复验（2026-08-20 真机优先方针）+ 回复 upstream issue #1
  - → `docs/journal/2026-08-21-issue1-v1-speed.md`

- [ ] **#176 busy 气泡「堆积消息」TOCTOU 竞态：turn 在气泡打开期间结束 → 消息入队后永不自动发** `queue` `session`
  - 弹气泡时 busy、点击「堆积消息」时 turn 已结束 → `enqueuePendingMessage` 无条件入队不重验 FSM 状态；而自动发送唯一触发器 `onNaturalTurnEnd` 在 turn 结束瞬间已 no-op 过（当时队列空）→ 消息滞留至手动「继续」
  - 修复与 **#177 统一**：状态补偿 drain（FSM Idle + 队列非空 → 发送）为 #177 的超集方案，一并覆盖本条
  - 代码锚点：`ui/screens/chat/input/SendStopButton.kt:217` · `ui/screens/chat/ChatViewModel.kt:158` · `data/repository/PendingMessagePipeline.kt:54,91`

- [ ] **#177 堆积队列退出会话/切后台后滞留：边沿触发无补偿 → 改状态驱动 drain** `queue` `session`
  - 三断点（2026-08-21 deep-explore 静态链验证）：①边沿错过即死（#176 同构）；②drain 时 POST 失败 → Idle+队列非空+无未来边沿的不动点（`PendingMessagePipeline.kt:92-95` "等下一次自然结束"假设结构性不成立）；③切后台断连后 L3 恢复的 RestValidation(Idle) 不在 naturalTurnEnd 白名单（`SessionStateService.kt:456-458`）→ 不推进
  - 已排除：listener 生命周期（应用级单例接线 `EventDispatcher.kt:139-142`，退出会话不丢）与 SSE 存活（FGS+WakeLock 保护）
  - 用户需求：会话退出后、app 切后台后队列均能自动发送；修复方向：应用级"FSM Idle + 队列非空 → drain"状态补偿（挂入既有 5s reconcile 循环 + enqueue 时查 statusFlow + RestValidation 确认 Idle 亦触发），统一覆盖 #176；手动「继续」入口可补会话列表长按

- [ ] **#178 点发送/拉起 busy 气泡时软键盘被收起——应保持拉起** `ui` `input`
  - 成因两路（2026-08-21 调查）：①气泡 `Popup(focusable=true)`（`SendStopButton.kt:223`）抢窗级焦点 → IME 收起；②发送路径无显式 hide，唯一候选是滚动触发 hide（`ChatScreen.kt:394-402`，发送后 forceScroll 在列表非底部时可能命中）
  - 修复方向：气泡 `focusable=false` 保持键盘，副作用是返回键不再触发 onDismissRequest——需补 BackHandler 关闭；发送路径先深挖 forceScroll→hide 是否真实触发再定改法

- [ ] **#179 消息气泡间距变大（主观）——常量未变，疑分片/空行化副作用，待定位** `ui`
  - 已排除：`messageSpacing`=8dp 未变（`ChatScreen.kt:763`）、SpacingTokens 未变；两嫌疑（中置信）：①0faa6984（08-20）分片重构把巨型 turn 拆 chunk，首末段各保留标签栏/统计栏+vertPad；②92e2855c（08-20）≥3000 字符段落空行化撑高气泡内部
  - 下一步需用户提供截图/确认是"气泡与气泡间"还是"气泡内部变高"再精确归因

- [ ] **#180 subagent 卡片进行中无法点击进入子会话（结束后可点）** `session` `ui`
  - 根因候选：点击导航依赖 metadata 中的 sessionId/jobId（`TaskToolCard.kt:84-98`），V2 服务器疑似仅在 completed 下发 childID（`V2Mappers.kt:326` 注释佐证）[推断] → Running 期间 clickAction=null，点击回落到展开切换而 output 为空 → 无可感知反应；且 `TaskToolCard.kt:101` showNavArrow 显式排除 isRunning（Running 时无导航箭头视觉提示）
  - 待确认：Running 期间 SSE tool part metadata 实际内容（真机 logcat）；修复方向：补齐 Running 期 childID 解析或从 step 事件流关联
  - 注：#148（08-16）「无法点击」为模拟器环境劣化已关闭，与本次主对话场景不同

- [ ] **#181 subagent 卡片缺展开/收起按钮（结束后导航态下 chevron 消失）** `ui`
  - 根因明确：`TaskToolCard.kt:113` `showExpandIcon = !showNavArrow`——卡片结束且有子会话时导航箭头与展开 chevron 互斥，chevron 被隐藏；同时标题行点击被导航覆盖（`ToolCardScaffold.kt:136` `onClick ?: onToggleExpand`）→ 展开入口完全消失，输出内容无法查看
  - 修复方向：chevron 与导航箭头并存（showExpandIcon 独立于 showNavArrow），与 #180 一并处理

- [ ] **#182 subagent 卡片展开内容截断——三层嫌疑，"之前修复"未动 UI/DB 两条截断链** `ui` `data`
  - 三层：①UI 硬截断 `output.take(2000)`（`TaskToolCard.kt:179`，"展示一半戛然而止"最直接嫌疑）；②DB 落库 500 字符预览（#79 e7ca830f，设计声明"内存渲染完整"但未覆盖重进会话从 DB 回读场景——回读后最多 500 字符）；③半屏限高 + verticalScroll（`TaskToolCard.kt:167`，可滚动、疑非根因）
  - git 考古：take(2000) 与 halfScreenHeight 均自旧提交 84476ccd 起未变，未见专门修复 commit [推断：用户记忆中的修复为 #79 落库批次或限高调整]
  - 修复方向：take(2000) 改为分片渲染或取消 + DB 回读场景需完整 output 与 500 字符预览的取舍重评（与 #79 的 DB 体积目标冲突，需设计）

- [ ] **#183 turn 分割线上下留空减半（用户期望明确）** `ui`
  - 现值：`padding(vertical = compact 3dp : 6dp)`，两处同改：完整气泡 `MessageCardAssistant.kt:248` + 分片 chunk `MessageCardAssistant.kt:558`（RenderItem.TurnDivider 渲染）
  - 改法：normal 6→3dp、compact 3→1.5dp；若减半后视觉仍偏高再查相邻 block spacing 叠加。markdown `---` 线（`MarkdownContent.kt:470`）仅 h1 下且非本条对象

## P2 — 优化与锦上添花

- [~] **#162 真机滚动"还是卡"→ 帧级取证三层根因全修——待用户验收（GKD 重开场景）** `ui` `perf`
  - 三根因全修：重组风暴（慢拖 janky 41.7%→0.88%）、巨型消息分片（p95 400ms→9ms 级）、GKD 无障碍税（环境因素，App 内无低风险修复）；GKD 关闭场景用户已验收"十分丝滑"
  - 遗留条件：GKD 重开且卡顿回归时按根因③结论处置
  - → `docs/journal/2026-08-20-scroll-jank-investigation.md`

- [~] **#163 真机滚动两问题（滑过气泡卡顿 + fling 下跳）——已修 f03a89d5，待验收手感** `ui` `perf`
  - 三件套：视口预解析驱动 + SafeFlingBehavior 限速 + 解析移出主线程；RESIZE 11→0、fling 自然跑满、逐帧异常 6→0
  - 待用户验收：滚动手感（限速档位/预解析距离可调）
  - → `docs/journal/2026-08-20-scroll-stability.md`

- [~] **#164 主对话抽屉高度统一（min = max = 75% 屏高）——待验收观感** `ui`
  - 四抽屉 + SystemPromptDialog 固定 75% 屏高；真机 E2E 像素级全 PASS（顶边逐像素一致、空内容撑满）
  - 待用户验收：空内容抽屉底部留白观感
  - → `docs/journal/2026-08-20-drawer-height-75.md`

## P3 — 观察与低价值改进

- [~] **#156 Room 缓存行 tokens 持久化缺口——已修，待用户验收** `data` `storage`
  - c71ac4ec：SSE_PRIORITY 合并 CAS 检测 tokens 变更→增量落库；真机 E2E 复验 PASS（44/45 落库，19.1 万行 logcat FATAL=0）
  - → `docs/journal/2026-08-19-final-regression.md`

- [~] **#157 离线态终端 sessionDirectory=null + 输入框层级缺失——观察①已修待验收** `terminal` `edge-case`
  - 观察① reloadDirectory 兜底已修（de96758c）；观察②定性为不可达路径关闭（离线冷启停在连接页无法进会话）
  - → `docs/journal/2026-08-20-scan-round2.md`

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [~] **#159 jumpLockActive 镜像标志应从 JumpNavigationController.phase 派生——核心已修，剩纯清理** `arch` `jump`
  - fire-time 门控已直读 isJumpInProgress 真源（88774278）；剩启动 key 与 B-F2 提交门控（带 2s 时窗语义需一并设计），删除全部手工写点后收口（~1h）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [~] **#160 LeakCanary 报 OpenCodeConnectionService$LocalBinder 泄漏——已修，待用户验收** `leak` `service`
  - d8331596：孤儿 job 取消 + SSE takeWhile 守卫 + connect 入口守卫 + HomeViewModel 卫生项（红绿验证，全量 1758 绿）；结构性根治（Router 抽取）按需另立项
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#161 离线时顶栏 context 圆环隐藏** `data` `ui`
  - contextWindow 仅存内存、依赖会话级 REST；现状代码注释已声明可接受，仅当期望离线可见才做（落库方向，~2h）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#165 长文本 Part 级 semantics merge（GKD 税缓解，条件性价值）** `perf` `a11y`
  - GKD 已长期关闭主收益消失；仅 GKD 用户重开才有价值。A/B 中止线已定：GKD 关 p50 回退 >2ms 或 p95 改善 <15% 即 abort（~3h）
  - → `docs/journal/2026-08-20-scroll-jank-investigation.md`（提升自该批子条目）

- [ ] **#166 RaceProbe 复现取证待用户执行** `race`
  - 若跳转叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可重放定位）
  - → `docs/journal/2026-08-21-race-audit-round6.md`（提升自该批子条目）

- [ ] **#167 overlay HUD 真机授权走查** `dev-infra`
  - 悬浮窗权限授予 + overlay 显示/dropCount 读数验证（代码已交付 dc57cba0，未真机走查）
  - → `docs/journal/2026-08-20-quick-jump-round4.md`（提升自该批子条目）

- [ ] **#168 慢拖残余 ~18ms 偶发尖刺——最低优先级** `perf`
  - F5 后残余（draw 4-8ms + input 3-5ms，12 轮仅 10 条）；「预取 idle_frame」候选已否证；release 口径 p95 7.9ms 已低于感知阈值，再深挖方向为 draw/input 相位本身（~2h）
  - → `docs/journal/2026-08-20-perf-monitoring-round3.md`（提升自该批子条目）

- [ ] **#184 未读水位线 globalMax 跨服务器混合——多服务器时钟偏差场景** `data`
  - markAllSessionsRead 对不分服务器的水位线 map 取全局 max（SessionListViewModel:423-430）——多服务器时钟不同域时一键已读可能错杀/漏杀红点；#171 grilling Q6 定案：不动存储 schema，登记不动
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [ ] **#185 V1/V2 god-client 拆解（终局债务，显式不做）** `refactor`
  - V1ApiClient(72 方法)/V2ApiClient(84) 全域 god-client + 7 门面 78 处 if 分发——#172 grilling Q1 定案：seam 已在门面 interface 正确收敛，拆轴属内部代码组织（22 测试文件重写 + 缓存式适配器版本竞态），显式登记不拆
  - → `docs/journal/2026-08-21-arch-review-deepening.md`
