# Chat UI 事件生命周期文档

> 本文档描述 OC Beacon 聊天界面的触摸事件、嵌套滚动、SSE 流式更新、
> 消息状态机的完整生命周期，以及已知的竞态条件和缓解策略。

---

## E1. 触摸事件传播链

### 事件分发顺序

```
手指 Down → Move → Up

Compose pointer event 分发（父→子）：
1. hitTest: 从根布局开始，确定哪些组件包含触摸点
2. 父节点 onPointerEvent(PointerEventType.Press) → 子节点 onPointerEvent
3. 子节点 consume() → 父节点检查 consumed 状态
4. 手指 Move → 同上流程
5. 手指 Up → 同上流程，触发 tap/longPress 识别
```

### ChatScreen 中的 pointerInput 使用位置

| 组件 | 位置 | 手势类型 |
|------|------|----------|
| LazyColumn | ChatScreen | detectTapGestures(onTap=hideKeyboard) |
| SelectionContainer | BashToolCard | 长按选择文字 |
| MarkdownPreviewDialog | dialog/MarkdownPreviewDialog.kt | SelectionContainer 长按选择 |
| Header Row | 各 ToolCard | clickable 切换展开 |

### 多指触控

Compose 的 pointer event 体系通过 pointerId 区分不同手指。ChatScreen 未使用多指手势。
SelectionContainer 内部长按选择时会消费 pointer events，阻止事件传播。

---

## E2. 嵌套滚动生命周期

### 回调触发顺序

```
手指 Down (不触发滚动回调)
手指 Move (delta ≠ 0):
  1. 父 onPreScroll(delta) → 父决定是否提前消费
  2. 子组件消费 delta (verticalScroll / LazyColumn)
  3. 父 onPostScroll(consumed, available) → 父决定是否消费剩余
  4. consumeBoundaryScroll.onPostScroll → 边界消费

手指 Up (触发 fling):
  5. 父 onPreFling(velocity) → 父决定是否提前消费速度
  6. 子组件执行 fling 动画
  7. 父 onPostFling(consumed, available) → 父决定是否消费剩余速度
  8. consumeBoundaryScroll.onPostFling → 边界消费
```

### consumeBoundaryScroll 介入节点

`consumeBoundaryScroll` 修饰符放在 `verticalScroll` 之前（modifier 链从外到内），
所以其 `NestedScrollConnection` 先于 `ScrollState` 接收事件。

```
[consumeBoundaryScroll] → [verticalScroll] → [内容]

onPostScroll:
  - scrollState.canScrollBackward == false (到达顶部) && available.y < 0 (向上拖)
    → 返回 available (消费，阻止传给 LazyColumn)
  - scrollState.canScrollForward == false (到达底部) && available.y > 0 (向下拖)
    → 返回 available (消费，阻止传给 LazyColumn)
  - 其他 → 返回 Offset.Zero (放行)

onPostFling: 同上逻辑但处理 Velocity
```

### reverseLayout=true 对方向的影响

ChatScreen 的 LazyColumn 使用 `reverseLayout = true`：
- 视觉上：最新消息在底部（index 0 = 视觉底部）
- 滚动方向：向下拖 → delta.y > 0 → LazyColumn 向尾部滚动（显示更旧的消息）
- verticalScroll 在工具卡片内不受 reverseLayout 影响

---

## E3. SSE 流式更新 → UI 重组

### 传播链路

```
SSE MessagePartDelta 事件
  → SseConnectionManager (data/network/SseConnectionManager.kt)
  → EventDispatcher.processEvent(event, serverId)
  → MessageEventHandler.handle(event, serverId)
  → _messages StateFlow 更新
  → ChatViewModel.combine { messages[sessionId] ... }
  → ChatUiState 更新
  → collectAsState() 触发
  → ChatScreen Recomposition
  → AssistantMessageCard / ToolCards 重组
```

### 事件去重和合并策略

- SSE 事件通过 `messageId + partIndex` 定位目标 Part
- `MessagePartDelta` 累积追加到现有 Part 的 text 字段
- `retainState = true`（MarkdownContent）保持旧渲染内容直到新内容解析完成，防止闪烁

### Recomposition 范围

- `collectAsState()` 在 ChatScreen 层级读取 `uiState`
- `uiState.messages` 变化 → `itemsIndexed` 重新执行
- 每条消息通过 `key(msg.message.id)` 稳定标识，只有变化的 item 会重组
- `AssistantMessageCard` 内部 `key(part.id)` 稳定 Part 重组

---

## E4. 消息状态机

```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  User 消息:                                                     │
│  Queued → Sending → Complete                                   │
│                                                                │
│  Assistant 消息:                                                │
│  (created) → Streaming (SSE MessagePartDelta) → Complete       │
│                          ↓                                      │
│                    Error (SSE Error)                            │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### UI 表现差异

| 状态 | User 消息 | Assistant 消息 |
|------|-----------|----------------|
| Queued | 灰色半透明 | — |
| Sending | 进度指示器 | — |
| Streaming | — | PulsingDotsIndicator，MarkdownContent retainState |
| Complete | 正常样式 | Footer 统计，可复制 |
| Error | — | ErrorPayloadContent 红色边框 |

### 状态转换触发

| 转换 | 触发条件 |
|------|----------|
| Queued → Sending | viewModel.sendMessage() |
| Sending → Streaming | SSE session.update + message.create |
| Streaming → Complete | SSE session.status=idle 或 REST fallback |
| Streaming → Error | SSE error 事件 |
| Complete → Streaming | 不可能（单向） |

---

## E5. 竞态条件清单

### 1. REST 加载 vs SSE 事件

- **触发条件**: `loadSession()` 的 REST 调用和 SSE 重连的事件几乎同时到达
- **现象**: 重复消息或丢失增量更新
- **缓解**: `EventDispatcher.mergeMessages()` 使用 ID 去重
- **测试**: 手动 — 杀掉 APP 进程后重新打开同一会话

### 2. scrollRestoreVersion 恢复 vs messageCount 自动滚动

- **触发条件**: `scrollRestoreVersion` 触发 `scrollToItem` 同时新消息到达触发自动滚动
- **现象**: 滚动位置跳动
- **缓解**: `autoScrollEnabled` 标志位，只在用户未手动滚动时自动跟随
- **测试**: 手动 — 长对话中滚动到中间，等待新消息到达

### 3. isAssistantContinuation key 过时（已修复）

- **触发条件**: 消息数量不变但内容变化（streaming 完成）
- **现象**: continuation 标记不更新
- **缓解**: 使用 `uiState.messages.map { it.message.id }` 作为 remember key
- **测试**: `TurnGroupCalculatorTest` 覆盖

### 4. consumeBoundaryScroll 方向在 reverseLayout 下的正确性

- **触发条件**: LazyColumn reverseLayout=true 时，工具卡片内 verticalScroll 的方向判断
- **现象**: 工具卡片到达边界后仍带动外层
- **缓解**: `consumeBoundaryScroll` 使用 `ScrollState` 的 `canScrollForward/Backward`，
  与 layout 方向无关
- **测试**: 手动 — 展开工具卡片，在内容顶部/底部继续拖动

### 5. Dialog 显示/隐藏与 Snackbar 时序

- **触发条件**: MarkdownPreviewDialog 的 onCopyAll 同时设置 clipboard 和显示 snackbar，
  然后 dialog 被关闭
- **现象**: Snackbar 可能在 dialog 关闭后无法显示
- **缓解**: SnackbarHost 在 dialog 内部，dialog 关闭时 snackbar 自然消失（可接受）
- **测试**: 手动 — 在弹窗中点击复制全部后关闭弹窗

---

## E6. 实现方式说明

| 章节 | 内容来源 |
|------|----------|
| E1 触摸事件 | Compose 源码 + ChatScreen.kt pointerInput 位置 |
| E2 嵌套滚动 | consumeBoundaryScroll (ChatModifiers.kt) 实现 |
| E3 SSE→UI | EventDispatcher → MessageEventHandler → ChatViewModel.combine |
| E4 状态机 | Message.kt + SseEvent.kt 状态定义 |
| E5 竞态条件 | ChatViewModel.kt + ChatScreen.kt 的 LaunchedEffect/DisposableEffect 交互 |
