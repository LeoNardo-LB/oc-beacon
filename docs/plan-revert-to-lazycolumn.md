# 计划：回归 LazyColumn(reverseLayout=true)

> **状态**：待批准
> **日期**：2026-06-15
> **根因分析**：systematic-debugging Phase 1-2 完成

## 1. 背景与根因

### 问题现象
SSE 流式输出时屏幕不断闪烁 + 内容来回震荡。

### 根因链
```
SSE token 到达
  → messageListState 新实例（MessageListState 重组）
  → ChatMessageList recompose → content lambda 引用变化
  → AnchoredLazyColumn 内 remember(content) 失效 → 全量重建 item 列表
  → 所有可见 item 重新 subcompose → 【闪烁】

同时：
  → snapshotFlow 检测到 fingerprint 变化 + isAtBottom=true（64px 容差内）
  → smoothScrollToBottom() = animateScrollToItem(0)
  → 动画进行中下一个 token 到达 → 新动画
  → 动画与新内容竞争 → 【震荡】
```

### 为什么 reverseLayout=true 不需要事后补偿

`reverseLayout=true` 的原生语义：
- item[0] 固定在视觉底部
- item[0] 高度增长时，LazyColumn 保持 `firstVisibleItemIndex` / `firstVisibleItemScrollOffset` 不变
- item[0] 底部边缘固定 → 新 token（文本末尾 = 视觉底部）天然出现在视窗内
- 上方已显示内容不受影响

**结论：不需要任何 smoothScrollToBottom/snapToBottom 来处理流式锚定。旧代码的自动滚动是画蛇添足，反而与原生锚定竞争导致震荡。**

## 2. 方案概述

| 文件 | 改动 | 风险 |
|------|------|------|
| `ChatMessageList.kt` | `AnchoredLazyColumn` → `LazyColumn(reverseLayout=true)` | 低 |
| `ChatScreen.kt` | `AnchoredLazyListState` → `LazyListState` + 删除激进自动滚动 | 中 |
| `AnchoredLazyColumn.kt` | 整个文件删除 | 零 |

## 3. 详细改动清单

### 改动 1: ChatMessageList.kt

#### 1a. import 替换（L20-21）
```diff
- import dev.leonardo.octether.ui.components.AnchoredLazyColumn
- import dev.leonardo.octether.ui.components.AnchoredLazyListState
+ import androidx.compose.foundation.lazy.LazyColumn
+ import androidx.compose.foundation.lazy.LazyListState
```

#### 1b. 参数类型替换（L84）
```diff
-    listState: AnchoredLazyListState,
+    listState: LazyListState,
```

#### 1c. 组件替换（L137-149）
```diff
-                AnchoredLazyColumn(
+                LazyColumn(
                     state = listState,
                     modifier = Modifier.fillMaxSize()
                         .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                     contentPadding = PaddingValues(
                         start = SpacingTokens.MD.dp,
                         top = SpacingTokens.SM.dp,
                         end = SpacingTokens.MD.dp,
                         bottom = SpacingTokens.SM.dp
                     ),
                     reverseLayout = true,
-                    isAtBottom = isAtBottom,
                     verticalArrangement = Arrangement.spacedBy(messageSpacing)
                 ) {
```
> **注意**：移除 `isAtBottom = isAtBottom` 参数（LazyColumn 无此参数）。
> `isAtBottom` 参数本身保留在 ChatMessageList 函数签名中（FAB 可见性仍需要），只是不再传给 LazyColumn。

#### 1d. 删除 snapToBottom 扩展函数（L430-439）
```diff
- /** Snap scroll to absolute bottom for reverseLayout AnchoredLazyColumn. */
- private suspend fun AnchoredLazyListState.snapToBottom() {
-     if (totalItemsCount == 0) return
-     scrollToItem(0)
-     repeat(3) {
-         delay(16)
-         if (!canScrollBackward) return
-         scroll { scrollBy(-10_000f) }
-     }
- }
```
> ChatMessageList.kt 中如果有调用这个 snapToBottom 的地方，改为调用 ChatScreen.kt 中的版本或内联。需要 grep 确认调用点。

### 改动 2: ChatScreen.kt

#### 2a. import 替换（L40）
```diff
- import dev.leonardo.octether.ui.components.AnchoredLazyListState
+ import androidx.compose.foundation.lazy.LazyListState
+ import androidx.compose.foundation.lazy.rememberLazyListState
```

#### 2b. listState 创建（L289）
```diff
- val listState = remember { AnchoredLazyListState() }
+ val listState = rememberLazyListState()
```

#### 2c. 【核心】删除激进自动滚动逻辑（L486-538）

删除整个 `LaunchedEffect(Unit) { snapshotFlow { ... }.conflate().collect { ... } }` 块。

这是震荡的直接根源。删除后：
- SSE 流式更新（fingerprint 变化）→ 不触发任何滚动 → reverseLayout 原生锚定
- 新消息到达（count 增大）→ 不触发自动滚动 → reverseLayout 保证新消息在底部出现

#### 2d. 清理 forceFollow 孤立代码

删除 L486-538 后，以下变量变成孤立代码（无消费者）：

**L315**: `var forceFollowUntil by remember { mutableLongStateOf(0L) }` → 删除
**L319**: `var userScrolledAwayInForceWindow by remember { mutableStateOf(false) }` → 删除

**L792-793**（afterSend 逻辑中）:
```diff
- userScrolledAwayInForceWindow = false
- forceFollowUntil = System.currentTimeMillis() + 2000
```
> 需要读取 L780-800 确认上下文，确保不破坏 afterSend 的其他逻辑。

#### 2e. 扩展函数类型替换（L1133-1159）

```kotlin
// smoothScrollToBottom: 改回基于 animateScrollToItem
private suspend fun LazyListState.smoothScrollToBottom() {
    animateScrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}

// snapToBottom: 改回基于 scrollToItem
private suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}
```

### 改动 3: AnchoredLazyColumn.kt

```
整个文件删除
```

## 4. 保留不动的部分

| 代码 | 保留原因 |
|------|----------|
| `isAtBottom` derivedStateOf（L299-307） | FAB "跳到底部"按钮可见性判断 |
| afterSend 中的 `smoothScrollToBottom()` | 发送消息后等新 item 出现 → 滚到底部，用户明确想看回复 |
| FAB 点击的 `snapToBottom()` | 用户明确点击"跳到底部" |
| `ScrollPositionChecker`（独立文件） | `isAtBottom` 判断逻辑 |

## 5. 执行顺序

遵循 ChatScreen.kt 编辑协议（不可并行，每次 edit 后编译）：

```
Step 1: 改动 ChatMessageList.kt（1a-1d）
        → compileDevDebugKotlin
        → commit "refactor: AnchoredLazyColumn → LazyColumn in ChatMessageList"

Step 2: 改动 ChatScreen.kt（2a-2e）
        → compileDevDebugKotlin
        → commit "fix: remove aggressive auto-scroll, trust reverseLayout anchoring"

Step 3: 删除 AnchoredLazyColumn.kt
        → compileDevDebugKotlin
        → commit "refactor: remove AnchoredLazyColumn"

Step 4: bump version → assembleDevRelease
        → push → tag → gh release create
```

## 6. 验证

### 编译验证
每个 Step 后运行 `.\gradlew :app:compileDevDebugKotlin`（120s 超时）。

### 功能验证（用户在真机上）
1. **SSE 流式输出**：无闪烁、无震荡，新 token 天然出现在底部
2. **用户向上滚动阅读旧消息**：SSE 更新不打扰视窗
3. **发送新消息**：自动滚到底部看到回复
4. **FAB 跳到底部**：点击后跳到底部
5. **下拉刷新**：加载旧消息功能正常

### 回退方案
如果回归后出现新问题：
```bash
git revert <commit-hash>
```
回到 AnchoredLazyColumn 状态。

## 7. 前置确认（执行前需读取的代码段）

- [ ] ChatMessageList.kt L165-430（items 声明，确认无其他 AnchoredLazyColumn 特有 API 使用）
- [ ] ChatScreen.kt L780-800（afterSend 逻辑，确认 forceFollow 清理不破坏其他逻辑）
- [ ] ChatScreen.kt 中所有 `smoothScrollToBottom` / `snapToBottom` 调用点（确认改为 LazyListState 后签名兼容）
