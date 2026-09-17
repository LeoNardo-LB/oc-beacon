# 消息卡片字段盘点与三段式改造依据（2026-09-12）

> 用途：为「消息层去气泡 → 头部标题栏 / 正文栏 / 尾部统计栏」设计提供字段依据。
> 方法：本地只读取证（app 源码 + docs + dsh web 构建产物），行号可复核。
> 状态：**盘点完成；字段分配待用户裁决**（见 §6）。

## 0. 坐标勘误

- `dsh-client-ui-conversation/lib/client.js` **不是**渲染层（composer/Lexical/QueueDock/surface 域）；
  dsh 的 assistant 字段权威在 **`dsh-client-ui-chat/lib/client.js`**（下称 chat.js）。
- `dsh-client-resources/lib/client.js` 只有 5.8KB，是插件入口，不含聊天 UI。

## 1. 三个服务器面

app 只有 `ServerType.OpenCode` / `ServerType.Dsh`（domain/model/ServerType.kt:16）；
opencode 面分 V1(1.18) 与 V2(2.x beta)，故实际是**三面**：**V1 / V2 / DSH**。

## 2. 消息与部件模型（app 侧）

- `Message` 仅 **User / Assistant 两个子类**（Message.kt:34-42）；`system` / `synthetic` / `shell` / `compaction`
  都是 `Message.User` 的 `role` 字符串变体（V2Mappers.kt:344-403、DshEventMapper.kt:1014-1053/1549-1556）。
- `Part` **19 个子类**（Part.kt:73-326）；`isBubbleRenderablePart` 过滤不可渲染者
  （StepStart/StepFinish/Snapshot/Subtask/Compaction/Agent/SessionTurn/Unknown/Deliverables，
  ChatParts.kt:14-27）。
- 包装 `MessageWithParts`（Message.kt:144-148）；UI 层 `ChatMessage` + isUser/isAssistant/isSynthetic
  （ChatUiState.kt:126-134）。

## 3. 字段 × server 可得性矩阵

| 字段 | V1 | V2 | DSH | 关键证据 |
|---|---|---|---|---|
| 角色/类型 | ✅ | ✅ | ✅ | Message.role |
| 时间戳 created/completed | ✅ | ✅ | ✅ | Message.TimeInfo |
| agent 名 | ✅ | ✅（缺省 "build"） | ❌ 消息级无（仅会话级 agentPreset） | V2Mappers:256；DshEventMapper:255/800 |
| 模型名 + provider | ✅ modelID/providerID | ✅ model.id/providerID | ⚠️ 形状不同：`data.message.source.{provider,model}`；**app 不读** | api-ref:4269-4270；V2Mappers:271-272；chat.js:6921-6927；DshEventMapper:1099-1129 建 Message.Assistant 时 modelId/providerId 恒 null |
| tokens input/output | ✅（覆盖语义） | ✅ | ✅ usage.inputTokens/outputTokens | api-ref:4276-4281；V2Mappers:276-286；DshEventMapper:1107-1112 |
| tokens reasoning/cache | ✅ | ✅ | ⚠️ 消息级无；turn 级派生有 | chat.js:6932/6960-6961/3545-3548；DshProjection.kt:15-19（投影 4 桶无 reasoning） |
| tokens total | ✅ 可选 | ✅ | ✅ usage.totalTokens 可选（app 自算 input+output） | chat.js:6944-6949；DshEventMapper:1111 |
| **cost** | ✅ 累加 | ✅ 双形态 | ❌ **全链无**（web 也不显示） | api-ref:4274/4780；V2SseMapper:181-185；chat.js 唯一命中为注释 :5256 |
| 耗时 runMs | ⚠️ 无现成字段（app 用 created→completed 跨度近似，被中断轮为 null） | ⚠️ 同 | ✅ turn 级 runMs = turn.end−turn.start | RenderableTurn.kt:230-234；chat.js:3653 |
| TTFT | ❌ | ❌ | ✅ turn 级 ttftMs | chat.js:3787-3790/3834；会话级投影 DshSessionMapper:143-144 |
| tokens/s | ❌ | ❌ | ✅ turn 级 tokensPerSecond | chat.js:3835；StatsPills:4007 |
| turn/step 序号 | ❌（step part 代偿） | 仅 step | ✅ data.turn/step | api-ref:4305-4306；V2SseMapper:150/177；chat.js:4492-4493（app 仅内部用于拆流式宿主） |
| 状态（中断/出错） | abort part / error | finish:"error"+aborted（合成 Part.Abort） | ✅ data.interrupted 布尔 | V2Mappers:327-340；chat.js:4501/4542；app 映射 finish="interrupted"（DshEventMapper:1123）**但无 UI 读点** |
| 服务端规范消息 id | ❌（本地 id 即 wire id） | ❌ | ✅ data.message.id → wireId | chat.js:4490；DshEventMapper:1126（feedback CAS 地址） |
| feedback 👍👎 | ❌ 无端点 | ❌ | ✅ feedback/message-* 族 | DshEventVocabulary:61/98-99；app 已消费（DSH-only，wireId 键 CAS） |
| 分支 fork | ✅ 端点 | ✅ | ✅ + branchUnavailable 判据 | V1ApiClient:254；V2ApiClient:1144；chat.js:3667/7209；app TurnLedgerRow:126-135 无禁用判据 |
| 撤销 revert | ✅ | ✅ | ❌ | — |
| 步骤数/工具数 | 派生 | 派生 | 派生 | TurnLedger.kt:40-70 |
| 产出文件 | ✅ | ✅ | ✅ | TurnDeliverables.kt:132-140 |
| 会话 context% | ❌ | ✅ 会话级 | ✅ 投影 | ChatTopBar.kt:117-149 |
| 会话 token/cost 桶 | ❌ | ✅ Session.tokens/cost | ✅ 投影 4 桶 | api-ref:4228-4234；ChatViewModel.kt:1096-1132 |

## 4. dsh web 已暴露但 app 未消费

1. per-turn tokenUsage 全桶 + routes（chat.js:7203/7212 → 面板 3490-3548）：含 reasoningTokens、totalTokens、routes[{provider,model}]。
2. turn 级 ttftMs（chat.js:3806-3838/7210）与 tokensPerSecond（:3835）。
3. turn 级 runMs（chat.js:3653）。
4. `message.source.{provider,model}`（chat.js:6922）—— **DSH 补模型名的唯一途径**，app 管线不读。
5. chunk usage 完整桶（normalizeUsage chat.js:6931-6964）—— app 直接 Ignored（DshEventMapper:1647）。
6. 气泡尾「已停止」（chat.js:3028-3030）—— app 的 finish="interrupted" 无 UI 读点（仅 merge 存储 MessageMergeEngine:550）。
7. branchUnavailable 禁用判据（chat.js:3667/7209，文案 :2688/2794）。
8. **sessionStats 投影呈现缺失**：app 已完整接线（ContextDetailDelegate:98、DshSessionMapper:136-148、DshEventMapper:295-315）
   但 **ContextDetailDialog 无 stats 渲染行**；web StatsPills 渲染 turns/steps/llmMs/toolMs/ttft 均值/速度（chat.js:3949-4007）。
9. MessageIconActions 的 clock:"end" 尾部时间（chat.js:1054-1057/1094）—— app 时间在标题栏 time.created。

## 5. 两端缺失项

- **opencode 系（V1/V2）没有**：TTFT / tokensPerSecond / decodeMs / turn 级 runMs（无 timing 概念）、
  turn 序号、投影族（tokenUsage/sessionStats/contextPressure/…）、消息反馈、interrupted 布尔、source.kind 注入标记。
- **DSH 没有**：**cost**（消息级/会话级/投影全链无 → app totalCost 在 DSH 下结构性恒 0）、
  消息级 modelID/providerID/agent/mode/path/variant/parentID/structured/finish（等价物见 §3）、
  消息级 reasoning/cache 桶（等价物在 chunk usage / turn 派生）。

## 6. 三段式分配建议（**待用户裁决**）

判据（拟）：① 身份/定位 → 顶部，计量/产物/动作 → 底部；② 跨轮稳定 → 顶部，随流式增长 → 底部（防顶部抖动）；
③ 高频动作 → 底部常显，低频/敏感 → 「展开更多」弹窗；④ 服务器缺失的字段整项不渲染（能力位门控），**形态不变**。

**头部标题栏（草案）**：角色标签 · agent 名 · 时间戳 · 状态徽标（流式/已中断/出错） · 轮次序号
**尾部统计栏（草案）**：计量（耗时 / TTFT / tokens/s / token 桶 / 成本）+ 产物（产出文件行）+
动作常显（复制 / 撤销 / 跳转 / 分支）+ 「展开更多」弹窗（👍👎 等低频项）+ 步骤/工具数摘要

> 尾部统计栏拟**吸收现在挂在气泡外的两行**：`MaybeTurnLedgerRow`（TurnLedgerRow.kt:171-189）+
> `MaybeProducedFilesRow`（ProducedFilesRow.kt:38-48）。

**待裁决开口项**：
1. 模型名放顶部还是底部？
2. 用户消息是否也去气泡（两端均为「用户留气泡、助手平面」）？
3. 「所有类型」边界：消息层角色去气泡，卡片层（工具/事件/压缩/提问卡）保留容器？

## 7. 骨架现状

统一容器 `MessageBubble` 已是三段式：① 标签栏（左图标+label+suffix / 中绝对时间 / 右 trailing，#312③）
MessageBubble.kt:98-142；② 正文栏（contentExpanded 可选 AV）:144-165；③ 统计栏（statsBar Row）:167-179。
assistant 三条渲染路径各自复用/重绘容器：`MessageCardAssistant`（MessageBubble）、
`ChunkedAssistantMessage`（自绘 Surface，MessageCardAssistant.kt:607）、
`SegmentedAssistantMessage`（自绘 Surface，:885）——三份重复 chrome，改造须同时处理并抽单点。

## 8. 相关文件

- 前序底稿：docs/research/2026-09-12-387-followup-discussion.md
- 卡片层规范：docs/specs/2026-08-24-card-unification-design.md（#215）
- UI 铁律：docs/ui-conventions.md:5-21（服务器类型只产生能力位差异）
- 旧裁决域：2026-08-12 三气泡统一容器（commit 7aa9788b）
