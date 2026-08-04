# 自动滚动 isAtBottom 判定修复

> **日期**: 2026-06-14
> **状态**: 设计确认 + 模拟器验证通过，待实施
> **关联问题**: 最后一条 agent 消息任意一像素可见时，视窗就被 SSE 流拖动滚动
> **方案**: 修正 reverseLayout 下 offset 语义误用

---

## 1. 问题现象

用户报告：**当视窗中出现最后一条 agent 消息的任意一点像素时，视窗都会随着 SSE 流输出一直被拖动滚动**。

期望行为：仅当用户视窗"贴底"时才允许 SSE 驱动滚动。不在底部时不滚动。

---

## 2. 根因分析

### 2.1 当前实现

`ChatScreen.kt:299-309`：
```kotlin
val isAtBottom by remember {
    derivedStateOf {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) true
        else {
            info.visibleItemsInfo.firstOrNull()?.let {
                it.index == 0 && it.offset <= 64  // ← BUG
            } ?: false
        }
    }
}
```

### 2.2 reverseLayout 下 offset 的真实语义

从 Compose 源码追踪：

| 步骤 | 源码 | 说明 |
|------|------|------|
| `position(mainAxisOffset)` | `this.offset = mainAxisOffset` | item 0 的 mainAxisOffset = 0 |
| `applyScrollDelta(delta)` | `offset += delta` | forward scroll delta 为负（Compose 文档确认） |
| `place()` 的 reverseLayout 转换 | 操作局部 IntOffset 变量 | **不影响** `LazyListItemInfo.offset` |

**推导**：`LazyListItemInfo.offset = mainAxisOffset + accumulated_delta`

| 场景 | accumulated_delta | offset |
|------|-------------------|--------|
| 贴底（scroll=0） | 0 | **0** |
| 用户向下拖 50px（forward scroll） | -50 | **-50** |
| item 0 只剩 1 像素可见 | -(item_height - 1) | **大负数** |

### 2.3 Bug 确认

`it.offset <= 64` 的 64px 容差**错误地包含了所有负数**。

- 贴底时 offset = 0 → `0 <= 64` = true ✓
- 用户滚动后 offset = -223 → `-223 <= 64` = **true** ✗ **BUG**

### 2.4 模拟器验证（2026-06-14）

在真实 app 中 swipe down（看更旧消息），logcat `SCROLL_DEBUG` 输出：

```
offset=-12   canScrollBackward=true  isAtBottom_result=true  ← BUG
offset=-47   canScrollBackward=true  isAtBottom_result=true  ← BUG
offset=-92   canScrollBackward=true  isAtBottom_result=true  ← BUG
offset=-121  canScrollBackward=true  isAtBottom_result=true  ← BUG
offset=-157  canScrollBackward=true  isAtBottom_result=true  ← BUG
offset=-223  canScrollBackward=true  isAtBottom_result=true  ← BUG
```

**三重交叉验证**：
1. ✅ offset 变为负数
2. ✅ `canScrollBackward=true`（Compose 原生 API 确认用户已离开底部）
3. ❌ `isAtBottom_result=true`（当前代码仍认为在底部）

`canScrollBackward=true` 与 `isAtBottom=true` 矛盾 = bug 确认。

### 2.5 为什么 64px 容差当初被加入

代码注释（ChatScreen.kt:291-298）说用 `!canScrollBackward` 太严格（fling/layout 抖动时瞬态变 true），改用 `offset <= 64`。

但开发者**误解了 reverseLayout 下 offset 的语义**——以为 offset 是"距底部的距离"（非负），实际在上滑后变负。64px 容差不仅没起到缓冲作用，反而包含了所有上滑情况。

---

## 3. 修复方案

### 3.1 核心改动

```kotlin
// 之前（BUG）：
it.index == 0 && it.offset <= 64

// 修复后：
it.index == 0 && it.offset >= -BOTTOM_TOLERANCE
```

其中 `BOTTOM_TOLERANCE = 1`（允许 1px 的 layout 抖动）。

### 3.2 修复后行为验证

| 场景 | offset | `offset >= -1`? | 正确? |
|------|--------|-----------------|-------|
| 贴底 | 0 | true | ✓ |
| layout 抖动（±1px） | -1 ~ 1 | true | ✓ |
| 用户滚动 10px | -10 | false | ✓ |
| 一像素可见 | ≈ -item_height | false | ✓ |
| 完全不可见 | 不在 visibleItemsInfo | false | ✓ |

### 3.3 为什么不用 `!canScrollBackward`

代码注释指出 `canScrollBackward` 在 fling 减速或 layout 重算时会瞬态变 true，导致 spurious snapToBottom。

`offset >= -1` 更稳定——offset 是 layout pass 的确定值，不会在 fling 期间瞬变。

### 3.4 forceFollow 不需要额外改动

`forceFollow`（发送后 2 秒强制跟随）的 `userScrolledAwayInForceWindow` 判定条件是 `!isAtBottom`。isAtBottom 修复后：
- 用户在 forceFollow 窗口内主动上滑 → isAtBottom 立即变 false → forceFollow 释放 → 停止强制跟随

这是**正确的行为**——用户发完消息后如果想上滑浏览，不应该被强制拉回。

---

## 4. 改动范围

| 文件 | 改动 |
|------|------|
| `ChatScreen.kt:305` | `it.offset <= 64` → `it.offset >= -BOTTOM_TOLERANCE` |
| `ChatScreen.kt`（新增常量） | `private const val BOTTOM_TOLERANCE = 1` |

**总改动量**：2 行。完全使用 Compose Foundation 原生 API，不引入第三方库。

---

## 5. 测试策略

### 5.1 手动测试场景

| # | 场景 | 预期 |
|---|------|------|
| 1 | 贴底时 SSE streaming | 视窗跟随滚动 ✓ |
| 2 | 用户上滑后 SSE streaming | 视窗**不**跟随 ✓ |
| 3 | 发送消息后（forceFollow 窗口）SSE streaming | 视窗跟随 2 秒 ✓ |
| 4 | forceFollow 窗口内用户上滑 | 立即停止跟随 ✓ |
| 5 | 用户上滑到 item 0 只剩 1 像素可见 | FAB 显示，不跟随 ✓ |
| 6 | 用户回到底部 | FAB 消失，恢复跟随 ✓ |

### 5.2 关键验证点

FAB（滚动到底部按钮）的显隐与 isAtBottom 共用同一判定。修复后 FAB 的行为也会正确：
- 贴底 → FAB 隐藏 ✓
- 用户滚动（即使 item 0 仍大部分可见）→ FAB 显示 ✓

---

## 6. Compose 源码参考

| 文件 | 关键代码 | 说明 |
|------|---------|------|
| `LazyListMeasuredItem.kt` | `position()` / `applyScrollDelta()` | offset 的计算逻辑 |
| `LazyListState.kt` | `scrollToBeConsumed` 注释 | "Scrolling forward is negative" |
| `LazyListLayoutInfo.kt` | `viewportStartOffset` / `viewportEndOffset` | viewport 边界定义 |

源码链接：
- https://android.googlesource.com/platform/frameworks/support/+/d43561c3dab4c0e7f5ae4b1e7c4b0d9b6b2338cb/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListMeasuredItem.kt
- https://android.googlesource.com/platform/frameworks/support/+/dcaa116fbfda77e64a319e1668056ce3b032469f/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
