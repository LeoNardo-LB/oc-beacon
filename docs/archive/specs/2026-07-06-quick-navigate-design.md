# 快速定位（Quick Navigate）功能设计

**日期**: 2026-07-06
**状态**: 已确认，待实现计划
**作者**: brainstorming session（用户 + AI 协作）

## 1. 目标与背景

在 oc-remote 的长对话中，用户难以快速回到之前某个提问的位置。本功能提供一个「快速定位」入口，列出当前会话所有用户提问，点击即可滚动跳转到对应位置。

灵感来自 DeepSeek 网页版的对话导航类油猴脚本（如 `deepseek-chat-navigator`、`ai-conversation-sidebar`），但落地为 oc-remote 的 Android 原生 UI。

## 2. 范围

### 做什么
- 在 ChatTopBar 溢出菜单新增「快速定位」项
- 点击后弹出 ModalBottomSheet，列出当前会话所有用户提问
- 列表项两行布局：序号 + 时间戳 / 提问原文（不截断，可横滑）
- 当前可见位置对应的提问自动高亮
- 点击列表项滚动跳转到对应消息

### 不做（YAGNI）
- 不做悬浮气泡触发（改用顶栏菜单，更干净）
- 不做手势触发（输入框上滑/聊天区上滑）——后续可选增强
- 不做跳转后目标消息的视觉高亮——现有代码无消息高亮机制，改动大，收益低
- 不做提问搜索/过滤——首版只列全部
- 不做提问收藏/书签——YAGNI

## 3. 设计决策摘要

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 触发入口 | ChatTopBar 溢出菜单项（第 2 位） | 零屏幕占用、零手势冲突、符合 Material 3 惯例、改动最小 |
| 展开形态 | 底部 `ModalBottomSheet` | Material 3 原生一等公民、移动端最熟悉、实现最简单 |
| 菜单项位置 | 「打开工作区」之后、「终端」之前（第 2 位） | 用户指定 |
| 菜单项文字 | 「快速定位」（`menu_quick_navigate`） | 用户指定 |
| 菜单项图标 | `Icons.AutoMirrored.Filled.FormatListBulleted` | 表达"提问清单" |
| 列表项布局 | 两行：Q1 + HH:mm / 提问原文（不截断横滑） | 用户指定 |
| 当前提问高亮 | 主色左竖条 + 淡背景 | 提升定位体验 |
| Sheet 高度 | 默认半屏，可上拖全屏 | `skipPartiallyExpanded=false` |
| 跳转后消息高亮 | 不做 | YAGNI |

## 4. 详细设计

### 4.1 入口（ChatTopBar 菜单项）

在 `ChatTopBar.kt` 现有溢出菜单（`DropdownMenu`，line 151-272）中，在「打开工作区」项（line 157）之后、「终端」项（line 168）之前，新增一项：

```kotlin
DropdownMenuItem(
    text = { Text(stringResource(R.string.menu_quick_navigate)) },
    onClick = {
        showMenu = false
        onQuickNavigate()
    },
    leadingIcon = {
        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null)
    }
)
```

**参数变更**：`ChatTopBar` 签名新增 `onQuickNavigate: () -> Unit`。

**可用条件**：沿用现有逻辑——菜单仅在 `sessionParentId == null`（parent session）时显示。不额外加消息数判断（简化实现）。空会话时菜单项仍可点，Sheet 显示空状态文案（见第 8 节）。

### 4.2 BottomSheet 内容（QuickNavigateSheet）

新增组件 `chat/components/QuickNavigateSheet.kt`：

```
┌──────────────────────────────────┐
│  ━━━━                            │  drag handle（Material 自带）
│  快速定位                     ✕  │  标题 + 关闭
├──────────────────────────────────┤
│ ▎Q1  14:32                       │ ← 高亮(主色左条+淡背景)
│   怎么配置 Ktor 的 SSE ←可横滑→  │
│ ─────────────────────────        │  分隔线
│   Q2  14:35                      │
│   报错日志显示 connection ←滑→   │
│ ─────────────────────────        │
│   Q3  14:41                      │
│   再帮我优化一下这个函数 ←滑→    │
└──────────────────────────────────┘
```

**列表项结构**：
- 第一行：`Q$n`（序号）+ 时间戳（`HH:mm`，由 `message.time.created` 格式化）
- 第二行：提问原文（`parts.filterIsInstance<Part.Text>().firstOrNull()?.text`），不截断，用 `Modifier.horizontalScroll(rememberScrollState())` 支持左右滑动
- 高亮项：左侧主色竖条（`Modifier.border` 或 `drawBehind`）+ `MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)` 淡背景

**Material 3 用法**：
```kotlin
if (show) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        // 标题栏 + LazyColumn(jumpTargets)
    }
}
```

**分隔线**：列表项之间用 Material 3 `HorizontalDivider`（轻量），或用 `Arrangement.spacedBy` + 卡片样式。遵循项目现有列表样式。

### 4.3 实现落点与数据流

#### 文件改动清单

| 文件 | 改动 | 内容 |
|------|------|------|
| `chat/util/JumpTargetExtractor.kt` | 新增 | 纯函数 util |
| `chat/components/QuickNavigateSheet.kt` | 新增 | ModalBottomSheet UI 组件 |
| `chat/components/ChatTopBar.kt` | 改 | 加 `onQuickNavigate` 参数 + 第 2 位菜单项 |
| `chat/components/ChatMessageList.kt` | 改 | 收 `showQuickNavigate` 参数；算 `currentQuestionRawIndex`；渲染 Sheet；实现跳转 |
| `ChatScreen.kt` | 改 | 加 `var showQuickNavigate by remember`；串联 TopBar ↔ MessageList |
| `res/values*/strings.xml`（15 locale） | 改 | 新增 `menu_quick_navigate` = "快速定位"（lokit 同步） |

**不动**：ChatViewModel、Domain 层、Data 层、Message 模型、网络层、SSE 管线。纯 UI 层 + 1 个纯函数 util。

#### 数据流

```
点菜单「快速定位」
   │ ChatTopBar.onQuickNavigate()
   ▼
ChatScreen: showQuickNavigate = true
   │
   ▼
ChatMessageList(showQuickNavigate=true)   ← 作用域内已有: listState, rawMessages, displayItems
   ├─ extractJumpTargets(rawMessages) → List<JumpTarget>          [纯函数]
   ├─ derivedStateOf { findCurrentQuestionRawIndex(...) }         [响应式高亮]
   └─ QuickNavigateSheet(show, jumpTargets, currentRawIndex, onJump, onDismiss)
        │ 点某项
        ▼ onJump(msgId)
   ChatMessageList:
   → displayItems.indexOfFirst { it.second.message.id == msgId } → displayItemIndex
   → lazyIndex = bannerCount + displayItemIndex
   → LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
   → onDismiss()
```

## 5. 技术细节

### 5.1 数据结构

```kotlin
// JumpTargetExtractor.kt
data class JumpTarget(
    val label: String,        // "Q1", "Q2" ...
    val timestampMs: Long,    // message.time.created（epoch 毫秒）
    val preview: String,      // 第一个 Part.Text 的 text；无文本时用占位
    val rawIndex: Int,        // 在 rawMessages 中的 index
    val msgId: String         // message.id，跳转反查用
)
```

### 5.2 extractJumpTargets（纯函数）

```kotlin
fun extractJumpTargets(rawMessages: List<ChatMessage>): List<JumpTarget> {
    var q = 0
    return rawMessages.mapIndexedNotNull { i, cm ->
        if (!cm.isUser) null
        else {
            q++
            JumpTarget(
                label = "Q$q",
                timestampMs = cm.message.time.created,
                preview = cm.parts.firstOrNull { it is Part.Text }
                    ?.let { (it as Part.Text).text }
                    ?: "(无文本)",   // 纯附件提问占位，可改 "(图片)"/"(文件)"
                rawIndex = i,
                msgId = cm.message.id
            )
        }
    }
}
```

注意：`ChatMessage.isUser` 已存在（`ChatViewModel.kt:201`）。`Part.Text` 在 `domain/model/Part.kt`。

### 5.3 findCurrentQuestionRawIndex（识别当前提问）

**核心约束**（调研确认）：
1. `ChatMessageList` 的 LazyColumn 使用 `reverseLayout = true`（`ChatMessageList.kt:265`）——index 0 在底部（最新），index 最大在顶部（最老）
2. 消息列表 `itemsIndexed` 之前有动态数量的条件渲染 item：`revert_banner` / `compaction_banner` / `retry_banner` / `tool_progress` / `step_progress` / `question_*`（多个）/ `perm_*`（多个）
3. 消息 item 的 key 格式：`"u_<messageId>"`（user）或 `"t_<messageId>"`（assistant，基于下一个消息 id），见 `ChatMessageList.kt:391-392`
4. `displayItems` 元素是 `Pair<rawIndex, ChatMessage>`（`ChatMessageList.kt:387`），rawIndex 是原始 `rawMessages` 的 index

**识别方案**（纯响应式，基于 `listState.layoutInfo.visibleItemsInfo`）：

```kotlin
fun findCurrentQuestionRawIndex(
    listState: LazyListState,
    rawMessages: List<ChatMessage>
): Int? {
    val visibleMsgs = listState.layoutInfo.visibleItemsInfo.filter { info ->
        (info.key as? String)?.let { it.startsWith("u_") || it.startsWith("t_") } ?: false
    }
    // reverseLayout 下，视觉最顶部 = offset 最小的可见消息
    val topMsg = visibleMsgs.minByOrNull { it.offset } ?: return null
    val key = topMsg.key as String
    val msgId = key.removePrefix("u_").removePrefix("t_")
    val rawIdx = rawMessages.indexOfFirst { it.message.id == msgId }
    if (rawIdx < 0) return null
    // 向前找最近的 user message（含自身）
    return (rawIdx downTo 0).firstOrNull { rawMessages[it].isUser }
}
```

在 `ChatMessageList` 中用 `derivedStateOf` 包裹，自动响应滚动：

```kotlin
val currentQuestionRawIndex by remember {
    derivedStateOf { findCurrentQuestionRawIndex(listState, rawMessages) }
}
```

### 5.4 跳转与 banner offset 处理

跳转目标 msgId → LazyList 绝对 index 的转换需要 `bannerCount`（消息 item 之前的 item 数量）。

**bannerCount 计算**（在 `ChatMessageList` 渲染作用域内，所有条件可见）：
```kotlin
val bannerCount = remember(...) {
    listOfNotNull(
        sessionMeta.revert?.let { 1 },          // revert_banner
        compactionState?.let { 1 },             // compaction_banner（需确认字段名）
        retry?.let { 1 },                       // retry_banner
        toolProgress?.let { 1 },                // tool_progress
        stepProgress?.let { 1 },                // step_progress
    ).sum() + pendingQuestions.size + pendingPermissions.size
}
```

> 实现时需对照 `ChatMessageList.kt` 实际的条件变量名（line 273-385）精确计算，确保与渲染逻辑一致。建议封装为一个 `derivedStateOf` 避免不一致。

**跳转实现**：
```kotlin
fun jumpToMessage(msgId: String) {
    val displayItemIndex = displayItems.indexOfFirst { it.second.message.id == msgId }
    if (displayItemIndex < 0) return
    val lazyIndex = bannerCount + displayItemIndex
    coroutineScope.launch {
        LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
        onDismiss()
    }
}
```

`LazyListReflection.requestScrollToItemNoCancel` 已存在（`ChatMessageList.kt:643`），复用。

### 5.5 时间戳格式化

复用项目现有模式（参考 `MessageCard.kt:243-245`）：
```kotlin
val timeFmt = remember(timestampMs) {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}
val timeText = timeFmt.format(Date(timestampMs))
```

## 6. 关键约束（实现时务必遵守）

来自 `AGENTS.md` 与现有代码：

1. **ChatScreen.kt 编辑协议**（`docs/chatscreen-editing-protocol.md`）：串行编辑、先 Read 再 Edit、每次改完跑 `compileDevDebugKotlin`、编译失败先 `git checkout`。**本功能主要改 ChatMessageList.kt 和 ChatScreen.kt，必须遵守此协议。**
2. **SSE 滚动稳定性**：本功能不触碰 `Markdown()`、`scheduleFlush()`、`layout{}` 补偿、`autoScrollEnabled` 等机制，跳转用的是 `requestScrollToItemNoCancel`（已存在，不取消进行中的滚动），不破坏流式滚动。
3. **Material 3 First**：用原生 `ModalBottomSheet` / `HorizontalDivider` / `DropdownMenuItem`，不自定义 Canvas。
4. **Theme Token System**：高亮背景用 `AlphaTokens.SELECTED`，间距用 `SpacingTokens`，不自造 alpha/spacing。
5. **reverseLayout**：所有 index 推理必须考虑 reverseLayout，视觉"顶部"= offset 最小，不是 index 最小。
6. **PathUtils**：本功能不涉及路径，无需注意。
7. **lokit**：新增字符串后跑 `lokit` 同步 15 locale。

## 7. 验证标准

### 功能验证
- [ ] 顶栏溢出菜单出现「快速定位」项（仅 parent session）
- [ ] 点击后 ModalBottomSheet 从底部弹起，列出所有用户提问
- [ ] 列表项两行：Q$n + HH:mm / 提问原文
- [ ] 提问原文超长时可左右滑动
- [ ] 滚动聊天区时，Sheet 中对应提问自动高亮
- [ ] 点击某项 → Sheet 关闭 + 平滑滚动到对应消息
- [ ] 流式输出中点击跳转，不破坏 SSE 滚动稳定性

### 边界验证（第 8 节）
- [ ] 空会话：菜单项可点，Sheet 显示空状态文案
- [ ] 1 个提问：Sheet 显示 1 项，跳转可用
- [ ] banner 出现/消失时（revert/question/permission），跳转目标 index 正确

### 编译与测试
- [ ] `.\gradlew :app:compileDevDebugKotlin` 通过（120 秒超时）
- [ ] `.\gradlew :app:testDevDebugUnitTest --rerun` 通过（180 秒超时）
- [ ] 新增 util 纯函数有单元测试（`extractJumpTargets` / `findCurrentQuestionRawIndex`）

## 8. 边界情况处理

| 场景 | 处理 |
|------|------|
| 0 个用户提问 | Sheet 显示空状态文案（如"暂无提问"，用 stringResource） |
| 1 个用户提问 | 正常显示 1 项，跳转可用 |
| 提问很多 | Sheet 内部 `LazyColumn` 滚动；展开时可选滚到当前高亮项 |
| 纯附件提问（无 Part.Text） | 预览显示占位文本（"(无文本)" 或按附件类型细化） |
| 流式输出中 | 菜单可用，跳转基于当前已渲染消息；跳转后新 token 继续追加（不冲突，因用 requestScrollToItemNoCancel） |
| 分页加载（loadOlderMessages）后 | rawMessages 变化，extractJumpTargets 重新计算，列表自动更新 |
| 子会话（sessionParentId != null） | 菜单不显示（沿用现有逻辑），功能不可达 |

## 9. 后续可选增强（不在本期）

- 手势触发（聊天区底部边缘上滑）
- 提问搜索/过滤
- 提问收藏/书签
- 跳转后目标消息的视觉高亮
- 悬浮气泡触发（若用户后续觉得菜单不够顺手）

## 10. 参考实现

- `widechaos/deepseek-chat-navigator`（DeepSeek 对话导航油猴脚本）
- `kuilei98/ai-conversation-sidebar`（三合一 AI 侧边导航）
- 项目内 `TurnGroupCalculator.kt`（turn 分组思路）
- 项目内 `MessageCard.kt:243`（时间戳格式化模式）
- 项目内 `ChatMessageList.kt:643 LazyListReflection.requestScrollToItemNoCancel`（跳转函数）
