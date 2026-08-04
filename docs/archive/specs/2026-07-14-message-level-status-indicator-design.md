# 消息级状态指示器设计

> 日期：2026-07-14
> 状态：已批准，待编写实现计划

## 1. 目标

将会话繁忙状态的视觉提示从顶部进度条迁移到每条消息的统计栏中，让用户直观看到"我的消息到哪一步了"和"agent 正在回复哪条"。

## 2. 背景

### 当前实现

- **顶部进度条**（`ChatScreen.kt:638-646`）：indeterminate `LinearProgressIndicator`，在 `SessionStatus is Busy || Retry` 时显示在 TopBar 下方。会话级粒度，不区分哪条消息在忙。
- **QUEUED 徽章**（`MessageCardUser.kt`）：橙色文字徽章，左对齐在时间旁边。当 FSM = Busy 且存在未完成 assistant 消息时，该 assistant 之后的 user 消息标记为 queued。
- **Assistant 统计栏**（`MessageCardAssistant.kt`）：仅在 `stepFinishes.isNotEmpty()` 时渲染（即 turn 完成后）。包含 token 统计（↑input ↓output）、时长、Provider/Model 等。
- **消息模型**：`TimeInfo(created, completed?)`，`completed == null` 表示消息未完成。
- **FSM 状态系统**：`SessionStatus`（Idle/Busy/Retry）+ `SessionActivity`（Waiting/Streaming/ToolCalling/Compacting），由 `SessionStateService` 驱动，SSE 事件输入。

### 数据源

| 数据 | 来源 | 说明 |
|------|------|------|
| `pendingMessageIds` | `MessageDataDelegate._pendingMessageIds`（本地 `MutableStateFlow`） | 跟踪 `sendParts` API 调用在途，ID 为 `"pending-${UUID}"`，非真实消息 ID |
| `queuedMessageIds` | `MessageDataDelegate` 从 FSM status + 消息列表推导 | FSM = Busy 时，最后一个未完成 assistant 之后的所有 user 消息 |
| FSM status / activity | `SessionStateService.statusFlow` / `activityFlow` | 由 SSE 事件（`session_status`、`session.next.*`）驱动 |
| Assistant 完成状态 | `Message.Assistant.time.completed` | `null` = 未完成（流式中） |

### QUEUED 覆盖缺口

当前 `queuedMessageIds` 逻辑：FSM = Busy 时查找最后一个 `completed == null` 的 assistant 消息，其后的 user 消息为 queued。当用户发送第一条消息、assistant 尚未创建消息时（`Busy + Waiting`），没有 pending assistant，`queuedMessageIds` 为空——这条 user 消息既不在 pending 也不在 queued，处于悬空状态。本设计通过新增"等待回复"状态覆盖此缺口。

## 3. 状态模型

### 3.1 用户消息

4 个状态，其中"发送中"不在消息级别体现：

| 状态 | 触发条件 | 视觉 | 数据源 |
|------|---------|------|--------|
| 发送中（隐式） | `sendParts` API 调用在途 | 输入栏发送按钮 loading（现有 `isSendingValue`） | `pendingMessageIds`（本地） |
| 排队中 | `id in queuedMessageIds` | QUEUED 徽章（右对齐） | FSM Busy + pending assistant 推导 |
| 等待回复 | FSM Busy，非 queued，且后续 assistant 未完成 | 圆环 `outline` 色，旋转 | FSM status + 消息列表推导 |
| 已完成 | FSM Idle 或后续 assistant `completed != null` | 无指示器 | — |

**QUEUED 徽章与圆环互换**：排队时显示徽章，排队结束后徽章消失、圆环出现，完成后圆环消失。两者不共存，占据统计栏同一位置。

### 3.2 Assistant 消息

2 个状态：

| 状态 | 触发条件 | 统计栏内容 | 圆环 |
|------|---------|-----------|------|
| 回复中 | `time.completed == null` | `[时间] [Agent标签] [圆环]` | `primary` 色，旋转 |
| 已回复 | `time.completed != null` | `[时间] [Agent] [Provider+Model] [时长] [Copy]` | 消失 |

**流式期间显示精简 footer**：当前代码仅在 `stepFinishes.isNotEmpty()` 时渲染 footer，需扩展为流式期间也显示（仅时间 + Agent + 圆环，无 token 统计/时长/Provider 信息，因这些在流式期间不可用）。

**移除 token 统计**：完成后 footer 也不再显示 `↑input ↓output`。

## 4. 视觉规格

- **组件**：`CircularProgressIndicator`（indeterminate），Material 3 原生
- **尺寸**：`Modifier.size(16.dp)`，`strokeWidth = 2.dp`
- **颜色**：
  - 用户·等待回复：`MaterialTheme.colorScheme.outline`
  - Assistant·回复中：`MaterialTheme.colorScheme.primary`
- **trackColor**：`MaterialTheme.colorScheme.surfaceVariant`（与 ChatTopBar context 指示器一致）
- **位置**：统计栏右对齐区域，Undo/Copy 按钮左侧
- **动画进出**：`AnimatedVisibility` + `fadeIn/fadeOut`（`tween(AppMotion.SHORT)`）

## 5. 统计栏布局变更

### 5.1 用户消息统计栏（`MessageCardUser.kt`）

```
当前: [时间] [QUEUED左对齐] ....空白.... [Undo] [Copy]
改为: [时间] ....空白.... [QUEUED或圆环] [Undo] [Copy]
```

QUEUED 徽章从左侧移到右侧，与圆环占同一位置（`AnimatedContent` 或条件渲染互换）。

### 5.2 Assistant 统计栏（`MessageCardAssistant.kt`）

```
当前(完成后): [时间] [Agent] [Provider+Model] [↑token ↓token] [时长] .... [Copy]
改为(回复中): [时间] [Agent] [圆环旋转]
改为(已回复): [时间] [Agent] [Provider+Model] [时长] .... [Copy]
```

- 去掉 token 统计（`↑${formatTokenCount(totalInput)} ↓${formatTokenCount(totalOutput)}`）
- 流式期间（`time.completed == null`）显示精简 footer：时间 + Agent 标签 + 圆环
- 完成后显示完整 footer：时间 + Agent + Provider/Model + 时长 + Copy

## 6. "等待回复"状态推导逻辑

新增状态推导函数，覆盖 QUEUED 缺口：

```kotlin
enum class UserMsgStatus { Queued, Waiting, Completed }

fun userMessageStatus(
    messageId: String,
    queuedMessageIds: Set<String>,
    fsmStatus: SessionStatus,
    messages: List<ChatMessage>
): UserMsgStatus = when {
    messageId in queuedMessageIds -> UserMsgStatus.Queued
    fsmStatus is SessionStatus.Busy -> {
        val nextAssistant = messages
            .dropWhile { it.message.id != messageId }
            .drop(1)
            .firstOrNull { it.isAssistant }
        if (nextAssistant?.message?.time?.completed == null) UserMsgStatus.Waiting
        else UserMsgStatus.Completed
    }
    else -> UserMsgStatus.Completed
}
```

逻辑说明：
1. 若消息在 `queuedMessageIds` 中 → Queued
2. 若 FSM = Busy，找此 user 消息之后的第一个 assistant 消息：
   - 不存在或 `completed == null` → Waiting（assistant 还没回复或正在回复）
   - `completed != null` → Completed（assistant 已回复完毕）
3. 若 FSM = Idle → Completed

此逻辑在 `MessageDataDelegate` 的 `messageListState` combine 中计算，随消息列表和 FSM 状态变化自动更新。

## 7. 移除项

| 移除内容 | 位置 | 原因 |
|---------|------|------|
| 顶部进度条 | `ChatScreen.kt:638-646` | 状态指示迁移到消息级 |
| QUEUED 徽章原位置 | `MessageCardUser.kt` 统计栏左侧 | 移至右侧与圆环互换 |
| Assistant token 统计 | `MessageCardAssistant.kt` footer | 不准确且占位 |

## 8. 不改动的部分

- **Retry 状态**：与圆环独立，单独提示（Snackbar 或其他方式），不影响消息级指示器
- **PulsingDotsIndicator**：工具卡片中的运行指示器保持不变
- **ReasoningBlock**：推理块的流式状态和计时器保持不变
- **SessionStateService FSM**：状态机逻辑不改动，仅消费其 `statusFlow` / `activityFlow`
- **输入栏发送按钮 loading**：保持现有 `isSendingValue` 行为
- **`streamingMsgId` 滚动补偿逻辑**：保持不变，仅用于滚动，不影响状态指示

## 9. 涉及文件

| 文件 | 改动类型 |
|------|---------|
| `ChatScreen.kt` | 移除顶部进度条（L638-646） |
| `MessageCardUser.kt` | QUEUED 徽章移至右侧，新增圆环指示器，`AnimatedContent` 互换 |
| `MessageCardAssistant.kt` | 流式期间显示精简 footer，移除 token 统计，新增圆环指示器 |
| `MessageDataDelegate.kt` | 新增 `userMessageStatus` 推导，输出到 `MessageListState` |
| `ChatViewModel.kt` | `MessageListState` 新增 `userMsgStatuses: Map<String, UserMsgStatus>` 字段 |
| `ChatMessageList.kt` | 传递 status 到 `MessageCardUser` |
| `MessageCard.kt` | `MessageCard` 参数透传 `userMsgStatus` |
| 新文件 `UserMsgStatus.kt` | 枚举定义 |

## 10. 测试策略

### 单元测试

- `UserMsgStatus` 推导逻辑：
  - Queued 消息 → `Queued`
  - Busy + 无后续 assistant → `Waiting`（覆盖缺口场景）
  - Busy + 后续 assistant `completed == null` → `Waiting`
  - Busy + 后续 assistant `completed != null` → `Completed`
  - Idle → `Completed`
- `MessageListState` 包含正确的 `userMsgStatuses` map

### Android 测试（Compose UI）

- 用户消息等待回复时显示圆环（`outline` 色）
- 用户消息排队时显示 QUEUED 徽章（右对齐）
- 用户消息完成后无指示器
- Assistant 流式期间显示精简 footer + 圆环（`primary` 色）
- Assistant 完成后显示完整 footer，无圆环
- Assistant footer 不包含 token 统计
- 顶部进度条不再显示

### 回归验证

- SSE 滚动稳定性不受影响（`streamingMsgId` 逻辑未改动）
- QUEUED 徽章功能正常（位置变更但不影响逻辑）
- Retry 状态不触发圆环
