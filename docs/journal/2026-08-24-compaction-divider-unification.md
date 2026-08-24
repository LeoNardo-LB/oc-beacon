# 2026-08-24 批次：压缩 UI 统一——分割线包揽一切（#217）

## 目标

V1/V2 压缩 UI 统一为单一分割线元素：进行中态（indeterminate 进度线 + 流式摘要可展开）→ 完成态（静分割线 + 无边框竖线式 Markdown 摘要可展开）。CompactionBanner 删除。

## 用户裁决记录（拷问三轮，2026-08-24）

- Q1 闪现元素=进行中气泡；Q2 手动/自动同路径
- Q3/Q5 **分割线包揽一切**（否决卡片包揽/双轨）
- Q4/Q9 进行中默认收起、点击展开看**实时流式摘要**（delta 接入）
- Q7/Q11 完成态展开**去边框**（用户：边框比视图窄一圈太丑）+ 左侧细竖线 + Markdown 渲染（bodySmall+降透明度）
- Q8 **进度线即分割线**（全宽 indeterminate 线 + 中央文字，完成时静置为分割线）
- Q10 展开态不记忆；Q13 进行中→完成保持展开无缝切换
- Q12 失败=分割线消失+保留 snackbar；Q6 完成 snackbar 保留
- 开工令：**V1、V2 都要支持**

## 取证（服务器探针 + 真机复现 subagent，证据 /tmp/compact-repro/）

### 服务器探针（curl + SSE tap，2026-08-24）

- V2 compact HTTP 立即返回（steer 异步）：CURL_DONE 351.893，压缩实际运行至 355.228
- 事件序：inbox.enqueued → compaction.started(含 reason/inputID) → delta×N(摘要流式,字段 text) → usage.updated → compaction.ended → execution.succeeded
- 消息列表：压缩消息 compact 请求后立即出现；**顶层 summary 字段完成后有全文（payload 恒空）**——V2Mappers text?:summary 兜底已覆盖
- V1 真相：V1 SSE 只有单个 session.compacted（无 started/delta 三件套；代码注释「V1 三件套」过时）；V1 HTTP summarize 同步挂起至完成

### 真机复现（subagent，round 1-4）

- **R1 banner 59ms 秒杀**：本地 finally CompactionEnded 注入（HTTP 16ms 返回后）→ SSE started 31ms 内复活；banner 实际连续显示，小会话 3-4s 感知即「一闪就没」
- **R3【新 bug】重复压缩刷新抑制**：ChatViewModel.kt:639-656 累积 Set compactedSessions 判变——同会话第二次压缩集合不变 → refreshMessages/snackbar 均不执行 → 全程零 UI，重进才见分割线（7 张截图+视频证实；与用户报告逐字吻合，疑似用户真实命中路径）
- **R4 全新会话 REST 路径健康**：banner 8.4s 连续、分割线 ended+37ms 入列、snackbar 遮挡 4s 后露出（corr 0.999997=纯遮挡）
- R2 服务器秒拒 compaction.failed "Nothing to compact yet"（预期）

## 实现清单

1. ServerCapabilities.compactionAsync（V2=true）
2. CompactionStateInfo + deltaText/messageId；CompactionDelta 累积器
3. SessionActionsDelegate：V1 HTTP 返回=终态；V2 仅失败兜底；不再注入 CompactionStarted/Ended
4. CompactionCard 重写（进行中/完成两态）
5. ChatMessageList：banner item 删除；进行中分割线插列表尾
6. ChatViewModel compactedSessions 判变修复（第二次压缩也刷新+通知）
7. CompactionBanner.kt 删除
8. 单测 + 编译 + 真机 E2E

## 执行记录

### 实现（编译绿 + 单测 1931 全绿）

| # | 文件 | 改动 |
|---|------|------|
| 1 | `domain/model/CompactionStateInfo.kt` | +deltaText/messageId（data 层同名类同步） |
| 2 | `domain/model/ServerConnection.kt` | +compactionAsync 能力位（V2=true） |
| 3 | `data/repository/handler/SessionNextEventHandler.kt` | CompactionDelta 累积器（乱序兜底置 active）+ started 记录 messageId/清 delta |
| 4 | `data/repository/handler/SessionEventHandler.kt` | R3 根治：compactedSessions Set 转 Map<String,Long> 计数（同会话多次压缩每次都发射） |
| 5 | `data/repository/EventDispatcher.kt` | 类型跟随 #4 |
| 6 | `ui/screens/chat/SessionActionsDelegate.kt` | compactSession V1/V2 分流：V1 本地置态+返回即终态；V2 零本地注入（SSE 全驱动）；compactionNotifier 链路删除 |
| 7 | `ui/screens/chat/ChatViewModel.kt` | 接线 compactionAsyncProvider/compactionLocalState；compactedSessions 收集改计数判变 |
| 8 | `ui/screens/chat/components/CompactionCard.kt` | 全量重写：ActiveDividerRow（进度线即分割线+可展开流式摘要）/ CompletedDividerRow（静分割线）/ ExpandContent（无边框+2dp 左竖线+Markdown bodySmall） |
| 9 | `ui/screens/chat/components/ChatMessageList.kt` | banner item 转尾部兜底分割线（messageId 不在渲染列表时）；消息流内卡片按 messageId 对位（Q13 同 item 原位切换）；displayItemMessageIds 判据 |
| 10 | `ui/screens/chat/components/CompactionBanner.kt` | 删除 |
| 11 | `测试` | CompactionDividerTest 7 用例（累积/二次重置/乱序/空 delta/计数）+ FullTest delta 语义更新 + ContextTokens/IntegrationTest Map 适配 |

### 真机 E2E（divider-e2e 会话，证据 /tmp/divider-e2e/）

- round 1（首次压缩）：seq-1 Compressing context: manual（进行中分割线）→ seq-2 Session compacted（snackbar）→ seq-4/6 Context compacted（完成分割线持续）
- round 2（R3 双连发）：r2-1 历史分割线+新 Compressing 并存 → r2-2 第二次压缩 snackbar 触发（修复前零反馈）→ r2-6 双 Context compacted 并存
- round 3（三次压缩）：logcat CompactionDelta 214 条逐条处理 + SessionCompacted 到达；数据链路完整
- 完成态展开：expanded.png——Context compacted + Markdown 全结构渲染（Objective/Important Details/Work State/Next Move/Relevant Files），无边框引用式
- V1 路径：无 V1 服务器环境，真机验证留待后续；逻辑同构（compactionAsync=false 本地置态+HTTP 返回即终态），行为由代码审查覆盖

### 验收要点（用户 V6）

1. 压缩（手动/自动）→ 一根分割线走全程：进行中（进度线+正在压缩上下文）→ 完成（静线+上下文已压缩），无气泡闪现
2. 同会话第二次压缩依然全反馈（snackbar+新分割线）
3. 进行中/完成态点击标签展开：流式摘要逐字生长 / Markdown 全文（无边框+左竖线）
4. 展开状态切换连续（进行中展开→完成保持展开）

