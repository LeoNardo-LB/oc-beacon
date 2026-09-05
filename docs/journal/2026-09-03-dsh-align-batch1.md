# dsh-align-batch1（2026-09-03）

> 状态：五子项已全部实现+AI 真机验收 10✔/2BLOCKED（2026-09-05 收尾,详见 `2026-09-04-fix-308-always-326-327.md` §十四 与 `docs/acceptance/2026-09-05-309-batch1-and-328.md`;项③④⑤ 由后续提交补齐——审计 `docs/research/2026-09-05-audit-309-313.md`）
> 关联：backlog **#309** · 路线 `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 1 · 挂点 `docs/research/dsh-gap-2026-09-01/implementability-ui.md`
> 来源：用户「设置目标继续」→ 目标第二项

## 项① goal.complete 第四钮（§11.4 #6：全场最便宜，S/2h 级）

- 数据面零增量：`DshApiClient.goalComplete`(:373)/`ChatRepositoryImpl.completeGoal`(:407) 全链早已在位，纯缺 UI 入口。
- 实现：GoalSheet 动作行第四钮（非 complete 相位常驻：active/paused/blocked 均可标记完成；完成后 phase=complete → 面板回创建表单，Web 语义对位）；ChatViewModel.completeGoal（pause/resume 同款 ref+reportGoalFailure 形状）；ChatScreen 单行接线（协议文件，Read→编辑→编译→commit 循环满足）。
- i18n：`goal_action_complete` 15 语言（en Mark complete / zh 标记完成 / ja 完了にする / ko 완료로 표시 / de Es Fr It PtRu Ru Tr Uk Pl Ar Id 全量）。

## 项② 压缩事件接线（§11.4 #11：UI≈0 纯接线）

- 服务端载荷定音（dsh-compaction-basic/lib/index.js，2026-09-03 源码）：`compaction/start={compactionId,sourceCommandId?,turn}`(:437)、`compaction/summary={compactionId,…,summary,shadowedRange,…}`(:589)、`compaction/end` 失败分支带 `error`(:463)。
- mapper 改动（DshEventMapper Tier2 区）：
  - start → `SessionNext(CompactionStarted)`（DSH 无 V2 message id/reason → 置空）；
  - summary → `SessionNext(CompactionDelta)`（单帧全文一次累积——CompactionCard 展开区实时渲染 deltaText）；
  - end 失败（error 非空）加发 `SessionNext(CompactionEnded(error))`——对位 #219 失败 snackbar 通道；banner 终结仍走 SessionCompacted → dispatcher endCompaction（既有）；
  - prune 维持 Ignored。
- 历史重放安全：DshHistoryFolder 与实况共用 mapSessionEvent——start→end 序列净零（banner 起→落），无跨重启残留。
- 事件复用既有 SseEvent 类（SessionNext/SessionCompacted 已在 dispatcher 注册表）→ **SseEvent 三步铁律无需新增 bind**。
- 测试：DshEventMapperTest 更新具名忽略目录（start/summary 移出）+ 新增 4 用例（start 映射/summary 全文映射/summary 缺文本维持忽略/end+error 双发）。

## 项④ 插话长按直发（§11.4 #4：S-M）

- 手势裁决：发送键长按原被 shell 切换占用（SendStopButton SendKey onLongClick）——改为**忙碌态长按=直发插话（steer），空闲态长按维持 shell 切换**（语义正交：shell+忙碌本就禁用；双键排队为主路径的定位不变）。
- wire：DshApiClient.promptAsync `put("mode", if (steer) "steer" else "queue")`（服务端 zod expected queue|steer，2026-08-31 E2E 实证注释在案）；steer 参数全链穿透 13 文件：SendKey/SendStopButton/ChatInputBar → ChatScreenBottomBar（发送主链原样提升为 sendFromComposer(steer)，confirm/shell/斜杠判定共用）→ ChatViewModel 门面 → ChatSendDelegate → SendMessageUseCase → ChatRepository(+Impl) → MessageApi(接口+路由) → V1/V2(忽略) → Fake(androidTest)。
- 测试：DshApiClientTest 新增 steer 用例（mode=steer 断言）；全链 mock 补第 8 参（6 个测试文件 14 处 7-any → 8-any）。

## 项⑤ 重试倒计时 + max-tokens continue（§11.4 #10：S-M，下轮实现）

wire 已服务端定音（2026-09-03 源码）：
- `llm/retry`（dsh-llm-retry/lib/index.js:100-122）：`{retryId, turn, step, provider, mode, policyKey, retry(次数), maxRetries?, delayMs(倒计时), failure}`；延迟到期实际重试再发 `llm/retry-started {retryId, turn, step, retry}`。
- `turn/end`（dsh-session/lib/types/types.d.ts:145-165）带 TurnEndReason `kind`：`blocked` / `error{error: LlmFailure}` / `max-tokens` / `interrupted`——现行 mapper 只取 time→SessionIdle，kind 整体丢弃（assistant/message 的 interrupted 前缀标记除外）。
- Web continue：dsh-client-ui-conversation 无专用 continue 端点——max-tokens 后走普通 session.prompt（下轮对照 Web UI 文案取样确认续写词）。
- 实现面挂钩：SessionStatus.Retry 已存在（ChatScreenBottomBar:331 已消费）；Part.Retry 已备（Part.kt:236）；V2 先例 retryState=attempt 计数（SessionNextEventHandler:165）。

## 项⑤ 重试倒计时 + max-tokens continue（实现，wire 定音见上节）

- `llm/retry` → `SseEvent.SessionStatus(Retry(attempt=retry, message=failure.message, next=time+delayMs))`——RetryBanner/FSM/通知全链现成（零新事件）；`llm/retry-started` → Busy（横幅退场）。历史重放安全：其后必有 turn/end（Idle）终态。
- `turn/end reason`（此前整体丢弃）：
  - kind=error → +`SseEvent.SessionError`——D1③ 转录内错误行 + sendMessage 清卡 + snackbar 双通道全现成；
  - kind=max-tokens → +`SseEvent.TurnMaxTokens`（**新事件，三步全走**：dispatcher bind 至 sessionNextHandler + extractSessionId + 状态表 `turnMaxTokens`；新一轮 turn/start/step/start（Busy）跨 handler 自动清卡）。
- UI：`TurnMaxTokensCard`（Web turn-max-tokens 通知节点对位；继续钮发 "continue" prompt——wire 无专用端点，续写词固定英文原词不本地化）；ChatMessageList 直采 `getTurnMaxTokensForSession`（compactionState 同款模式，零聚合器波及）。
- i18n：`turn_max_tokens_title`/`turn_max_tokens_continue` 15 语言（法/意无撇号）。
- 测试：DshEventMapperTest +4（retry 字段/计数、retry-started→Busy、error→idle+SessionError、max-tokens→idle+TurnMaxTokens）；noise 目录移除 llm/retry 两项。

## 验证记录

- `compileDevDebugKotlin` 通过；DshEventMapperTest 全绿（BUILD SUCCESSFUL）。
- 真机 E2E（待项③④⑤ 完成后统一执行）：goal 面板第四钮点击 → 投影回创建表单；/compact 命令 → 进行中分割线 + 摘要展开 + 完成 snackbar。

## 踩坑记录

（暂无——本轮 Kotlin 嵌套注释坑已在 fix-308 journal 记录）
