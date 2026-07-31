# SSE 流式渲染回归修复计划（beta.360 → HEAD）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 beta.360 之后引入的两个 SSE 流式渲染回归——(1) 流式输出时最后一个 turn 整体闪烁；(2) 所有卡片展开闭合后留下一大块空白。

**Architecture:** 基于 beta.360（mikepenz 0.41.0）与 HEAD（mikepenz 0.43.0）的精确 git diff 对比，定位到三处回归源：mikepenz 升级时丢失的 `immediate` 参数、commit `67e46011` 过度修复导致的 LaunchedEffect key 误伤、以及遗留的调试日志。修复策略是外科手术级回滚——只恢复被丢失的行为，不引入新抽象。

**Tech Stack:** Jetpack Compose、mikepenz multiplatform-markdown-renderer 0.43.0、kotlinx-coroutines

## Global Constraints

- **不回退 mikepenz 版本**：0.43.0 已绑定 compileSdk 37 + Kotlin 2.4，回退代价过大
- **不动 ChatScreen.kt 的 autoScrollEnabled LaunchedEffect**：保留 commit `67e46011` 对 autoScroll-lock 的修复
- **遵循 AGENTS.md SSE Scroll Stability 规则**：不引入 monostable height / heightMap 等已被废弃的补丁
- **ChatScreen.kt Editing Protocol**：每次编辑后必须运行 `compileDevDebugKotlin`
- **Gradle 超时**：Kotlin 编译检查设 120 秒超时
- **JDK 21** required

---

## 背景与症状

### 用户报告的两个问题

1. **流式输出闪烁**：SSE 流式输出时，最后一个 turn（streaming message）整体闪烁
2. **展开闭合留白**：所有卡片（不止思考卡片）展开后闭合，下方留下一大块空白，高度未还原

### 参照基准

**beta.360（v2.0.0-beta.360）"几乎完美"**——用户明确指出此版本稳定。本次修复以此为黄金参照，通过 `git diff v2.0.0-beta.360 HEAD` 精确定位回归源。

---

## 根因分析

### 回归源 1：mikepenz 0.41.0 → 0.43.0 升级丢失 `immediate` 参数

**位置**：`app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/markdown/MarkdownContent.kt`

**beta.360（稳定）**：
```kotlin
val markdownState = rememberMarkdownState(
    content = normalizedMarkdown,
    retainState = true,
    immediate = immediate  // ← assistant 消息传 true → 同步解析
)
```

**HEAD（回归）**：
```kotlin
val markdownState = rememberMarkdownState(
    content = normalizedMarkdown,
    retainState = true,
    // immediate 参数被移除
)
```

**根因机制**：

mikepenz 0.43.0 的 `rememberMarkdownState` 默认使用**异步解析**（官方文档明确："Creates a MarkdownState that parses content asynchronously"）。beta.360 用的 0.41.0 通过 `immediate = immediate` 参数强制 assistant 消息同步解析。

HEAD 升级到 0.43.0 时移除了 `immediate` 参数，代码注释错误地写道："Mikepenz Markdown parses synchronously so the immediate flag has no effect"——**这个注释是错的**。0.43.0 默认异步，必须显式传 `immediate = true` 才同步。

异步解析在流式场景下的危害：
- 每个 SSE token 触发 recomposition
- 异步解析让代码高亮、图片等异步元素处于不同加载状态
- 测量结果在 701px↔760px 之间震荡（commit `731b954e` 的 AAR 分析确认）
- LazyColumn 反复重排 → 整体闪烁

**mikepenz 官方文档确认**（Context7 查询）：
> `rememberMarkdownState` allows immediate parsing via the `immediate` parameter, though this is not recommended as it may block UI composition.

对于流式场景，"may block UI composition" 的代价远小于测量震荡，因此必须启用。

### 回归源 2：commit `67e46011` 误伤 ChatMessageList.kt 的 LaunchedEffect key

**位置**：`app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt:127`

**beta.360（稳定）**：
```kotlin
LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
    if (listState.isScrollInProgress) {
        compensateState.shouldCompensate = true
    } else if (isAtBottom) {
        compensateState.shouldCompensate = false
    }
}
```

**HEAD（回归）**：
```kotlin
LaunchedEffect(listState.isScrollInProgress) {  // ← 移除了 isAtBottom 作为 key
    if (listState.isScrollInProgress) {
        compensateState.shouldCompensate = true
    } else if (isAtBottom) {
        compensateState.shouldCompensate = false
    }
}
```

**根因机制**：

commit `67e46011` 的修复目标是 `ChatScreen.kt` 中 `autoScrollEnabled` 的 lock 问题（SSE layout 变化让 isAtBottom 短暂 true → autoScroll 锁死 → scrollToItem(0) 抖动）。但该 commit **同时修改了 `ChatMessageList.kt` 的 `shouldCompensate` LaunchedEffect**，移除了 `isAtBottom` 作为 key。

这是**误伤**——两个 LaunchedEffect 控制不同的状态：
- `ChatScreen.kt` 的 `autoScrollEnabled` → 控制 `scrollToItem(0)` 程序滚动
- `ChatMessageList.kt` 的 `shouldCompensate` → 控制 layout{} 中的视口补偿

移除 `isAtBottom` key 后，`shouldCompensate` 无法及时重置：
1. 程序滚动（messageCount 变化触发 `scrollToItem(0)`）→ `isScrollInProgress` 短暂 true → `shouldCompensate = true`
2. 滚动结束 → LaunchedEffect 重启 → 检查 `isAtBottom`
3. 由于 SSE 推送让内容增长，`isAtBottom` 可能短暂 false
4. `shouldCompensate` 保持 true（因为 isAtBottom 不是 key，其变化不再触发重启）
5. 用户展开卡片 → layout{} 中 `delta > 0` → 触发 `requestScrollToItemNoCancel` → `scrollOffset += delta`
6. 用户收起卡片 → `delta < 0` → **跳过补偿**（条件是 `delta > 0`）
7. `scrollOffset` 卡在过大值 → LazyColumn 视觉位置超过实际内容底部 → **下方留白**

`beta.360` 因为 `isAtBottom` 是 key，当用户回到底部时（或 SSE 推送让 isAtBottom 恢复 true 时），LaunchedEffect 重启，`shouldCompensate` 及时重置为 false，避免后续展开闭合触发错误补偿。

### 回归源 3：遗留调试日志（非用户报告，但应清理）

**位置**：`app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/PartContent.kt:154,157,160-166`

```kotlin
android.util.Log.e("PartContent", "TOOL ELSE: tool=${part.tool} ...")  // line 154
android.util.Log.e("PartContent", "isQuestionTool=...")                // line 157
dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "=== tool data ===")  // line 160
// ... 共 7 行 DebugLogger 调用
```

这些调试代码违反 commit `67e46011` 的 "All diagnostic logging removed for release" 承诺，且 `Log.e` 级别会污染 logcat。

---

## 历史修复脉络

这三个回归源都曾被反复修复，理解历史有助于避免再次引入：

| Commit | 修复内容 | 遗留问题 |
|--------|----------|----------|
| `46e65854` | restore isAtBottom as shouldCompensate reset condition | 只调整了重置条件，未恢复 LaunchedEffect key |
| `67e46011` | SSE scroll stability — 48ms flush, monostable height, autoScroll guard | **误伤** ChatMessageList.kt 的 LaunchedEffect key |
| `731b954e` | restore rememberMarkdownState as root cause of flicker | 恢复了 `retainState=true`，但**未恢复** `immediate` 参数 |

---

## File Structure

| 文件 | 职责 | 本次改动 |
|------|------|----------|
| `MarkdownContent.kt` | Markdown 渲染入口 | 恢复 `immediate` 参数 |
| `ChatMessageList.kt` | 消息列表 + 高度补偿逻辑 | 恢复 LaunchedEffect key |
| `PartContent.kt` | Part 渲染分发 | 删除调试日志 |

---

## Task 1: 恢复 MarkdownContent.kt 的 immediate 参数

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/markdown/MarkdownContent.kt:260-263`

**Interfaces:**
- Consumes: `immediate: Boolean` 参数（函数签名已保留，line 100）
- Produces: 稳定的 markdown 测量（消除流式闪烁）

- [ ] **Step 1: Read 当前代码确认状态**

Run: `Read app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/markdown/MarkdownContent.kt` offset=258 limit=10

Expected: 看到 `rememberMarkdownState(content, retainState=true)` **没有** `immediate` 参数

- [ ] **Step 2: 应用 Edit**

```kotlin
// 替换：
    val markdownState = rememberMarkdownState(
        content = normalizedMarkdown,
        retainState = true,
    )

// 为：
    // `immediate = immediate` is critical for streaming stability:
    // mikepenz 0.43.0 defaults to ASYNCHRONOUS parsing, which causes height
    // oscillation (701px↔760px) during streaming as async elements (code
    // highlights, images) load in different states across recompositions.
    // beta.360 (mikepenz 0.41.0) passed immediate=!isUser for assistant msgs,
    // forcing synchronous parse → stable measurement → no flicker.
    val markdownState = rememberMarkdownState(
        content = normalizedMarkdown,
        retainState = true,
        immediate = immediate,
    )
```

- [ ] **Step 3: 验证代码模式存在**

Run: `grep -n "immediate = immediate" app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/markdown/MarkdownContent.kt`

Expected: 至少 2 行匹配（函数参数声明 + rememberMarkdownState 调用）

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout: 120000ms)

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/markdown/MarkdownContent.kt
git commit -m "fix: restore immediate parameter to rememberMarkdownState

mikepenz 0.43.0 defaults to async parsing; without immediate=true
for assistant messages, streaming tokens trigger height oscillation
(701px↔760px) → LazyColumn flicker. Regression introduced when
upgrading from 0.41.0 (beta.360) to 0.43.0."
```

---

## Task 2: 恢复 ChatMessageList.kt 的 LaunchedEffect key

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt:123-133`

**Interfaces:**
- Consumes: `isAtBottom: Boolean`（函数参数已存在）
- Produces: `shouldCompensate` 及时重置（消除展开闭合留白）

**关键约束**：**只改 ChatMessageList.kt，不动 ChatScreen.kt**。ChatScreen.kt 的 `autoScrollEnabled` LaunchedEffect 保留 `isScrollInProgress` only key，维持 commit `67e46011` 对 autoScroll-lock 的修复。

- [ ] **Step 1: Read 当前代码确认状态**

Run: `Read app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt` offset=124 limit=12

Expected: 看到 `LaunchedEffect(listState.isScrollInProgress) {`（无 isAtBottom）

- [ ] **Step 2: 应用 Edit**

```kotlin
// 替换：
    // Track whether user has scrolled away from bottom.
    // Key is ONLY isScrollInProgress — NOT isAtBottom — so SSE layout changes
    // that briefly flip isAtBottom won't incorrectly toggle shouldCompensate.
    LaunchedEffect(listState.isScrollInProgress) {

// 为：
    // Track whether user has scrolled away from bottom.
    // Key includes isAtBottom so the effect restarts when SSE-driven layout
    // changes flip isAtBottom back to true — this lets shouldCompensate reset
    // promptly and prevents scrollOffset from being "stuck high" after a
    // user expands then collapses a card (which leaves phantom bottom space).
    // NOTE: ChatScreen.kt's autoScrollEnabled LaunchedEffect intentionally
    // keys ONLY on isScrollInProgress to avoid the autoScroll-lock regression
    // fixed in commit 67e46011. These two effects are independent.
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
```

- [ ] **Step 3: 验证代码模式存在**

Run: `grep -n "LaunchedEffect(listState.isScrollInProgress, isAtBottom)" app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt`

Expected: 1 行匹配

- [ ] **Step 4: 确认 ChatScreen.kt 未被误改**

Run: `grep -n "LaunchedEffect(listState.isScrollInProgress)" app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/ChatScreen.kt`

Expected: ChatScreen.kt 仍然是 `LaunchedEffect(listState.isScrollInProgress)`（**不带** isAtBottom）

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout: 120000ms)

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt
git commit -m "fix: restore isAtBottom as LaunchedEffect key for shouldCompensate

commit 67e46011 removed isAtBottom from the key to fix autoScroll-lock
in ChatScreen.kt, but also changed ChatMessageList.kt's shouldCompensate
effect — causing it to never reset when SSE pushes flip isAtBottom.
Result: scrollOffset stuck high after card expand/collapse → phantom
bottom space. Only ChatMessageList.kt is reverted; ChatScreen.kt keeps
the 67e46011 fix (the two effects are independent)."
```

---

## Task 3: 清理 PartContent.kt 遗留调试日志

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/PartContent.kt:154,157,159-166`

**Interfaces:**
- Consumes: 无
- Produces: 干净的 release 构建（无 logcat 污染）

- [ ] **Step 1: Read 当前代码确认调试日志位置**

Run: `Read app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/PartContent.kt` offset=148 limit=22

Expected: 看到 line 154, 157 的 `android.util.Log.e(...)` 和 line 160-166 的 `DebugLogger.log(...)`

- [ ] **Step 2: 删除 line 154 的 Log.e**

```kotlin
// 删除这一行：
                android.util.Log.e("PartContent", "TOOL ELSE: tool=${part.tool} state=${part.state::class.simpleName} outputLen=${toolOutput.length} outputHead=${toolOutput.take(200)}")
```

- [ ] **Step 3: 删除 line 157 的 Log.e**

```kotlin
// 删除这一行：
                android.util.Log.e("PartContent", "isQuestionTool=$isQuestionTool inputKeys=${toolInput.keys}")
```

- [ ] **Step 4: 删除 line 159-166 的 DebugLogger 块**

```kotlin
// 删除这 8 行（含注释）：
                    // Debug: log full tool data to find where answers live
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "=== tool data ===")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "input keys: ${toolInput.keys}")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "input: $toolInput")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "output: $toolOutput")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "metadata: ${completedState?.metadata}")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "title: ${completedState?.title}")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "tool name: ${part.tool}")
```

- [ ] **Step 5: 验证调试日志已清除**

Run: `grep -nE "android\.util\.Log|DebugLogger" app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/PartContent.kt`

Expected: 无匹配（或只匹配 import 行，不匹配调用）

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout: 120000ms)

Expected: `BUILD SUCCESSFUL`（如果失败提示 `DebugLogger` import 未使用，一并删除 import）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/PartContent.kt
git commit -m "chore: remove diagnostic logging from PartContent

Log.e and DebugLogger calls left over from question-tool debugging
violate the 'diagnostic logging removed for release' contract from
commit 67e46011."
```

---

## 验证策略

### 编译验证（每个 Task 必做）

```bash
.\gradlew :app:compileDevDebugKotlin
```
- Timeout: 120 秒
- 必须看到 `BUILD SUCCESSFUL`
- 失败时：`git checkout -- <file>`，重新 Read，重试

### 单元测试（可选，回归基线）

```bash
.\gradlew :app:testDevDebugUnitTest --rerun
```
- Timeout: 180 秒
- 确保未破坏现有测试（特别是 `MessageEventHandlerMergeTest`）

### 真机验证（用户必做）

由于这是 UI 渲染问题，自动化测试无法覆盖。用户需在真机上验证以下场景：

**场景 A：流式闪烁（Task 1 验证）**
1. 打开一个会话，发送需要长回复的消息（如"写一个 100 行的 Kotlin 类"）
2. 观察 SSE 流式输出过程
3. ✅ 通过：最后一个 turn 平稳输出，无闪烁
4. ❌ 失败：文字反复出现/消失或位置抖动

**场景 B：展开闭合留白（Task 2 验证）**
1. 流式输出过程中，点击展开任意卡片（思考/工具/编辑）
2. 等待 1-2 秒
3. 点击收起卡片
4. ✅ 通过：卡片收起后，下方无空白，内容紧贴
5. ❌ 失败：下方留下一大块空白

**场景 C：已完成消息的展开闭合（Task 2 补充验证）**
1. 在非流式状态下，展开任意已完成消息的卡片
2. 收起
3. ✅ 通过：无留白
4. ❌ 失败：留白

**场景 D：logcat 干净（Task 3 验证）**
1. `adb logcat | grep -E "PartContent|QuestionTool"`
2. 触发工具调用
3. ✅ 通过：无输出
4. ❌ 失败：有 Log.e 或 DebugLogger 输出

---

## 风险评估

### Task 1 风险：immediate 参数可能阻塞 UI

mikepenz 官方警告 immediate 解析"may block UI composition"。但：
- beta.360 已经验证此行为稳定（用户称"几乎完美"）
- 只对 assistant 消息启用（`immediate = !isUser`）
- assistant 消息的 markdown 通常不长，阻塞可忽略

**结论**：风险可控，与 beta.360 行为一致。

### Task 2 风险：恢复 isAtBottom key 可能重引入 SSE 视口抖动

理论上，如果 SSE 推送让 `isAtBottom` 高频抖动，LaunchedEffect 会频繁重启。但：
- beta.360 用此配置稳定运行
- `shouldCompensate` 重置为 false 后，layout{} 不再触发 `requestScrollToItemNoCancel`，不会抖动
- 真正的视口抖动来自 `autoScrollEnabled`（ChatScreen.kt），那个 LaunchedEffect **不动**

**结论**：风险可控，与 beta.360 行为一致。

### Task 3 风险：无

纯删除调试代码，无副作用。

---

## 附录 A：beta.360 vs HEAD 关键 diff 速查

### mikepenz 版本

| 版本 | build.gradle.kts |
|------|------------------|
| beta.360 | `val markdownRendererVersion = "0.41.0"` |
| HEAD | `val markdownRendererVersion = "0.43.0"` |

### rememberMarkdownState 调用对比

```diff
-    val markdownState = rememberMarkdownState(
-        content = normalizedMarkdown,
-        retainState = true,
-        immediate = immediate
-    )
+    val markdownState = rememberMarkdownState(
+        content = normalizedMarkdown,
+        retainState = true,
+    )
```

### LaunchedEffect key 对比（ChatMessageList.kt）

```diff
-    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
+    LaunchedEffect(listState.isScrollInProgress) {
```

---

## 附录 B：不应触碰的相关代码

以下代码看似相关但**不应修改**（已验证是正确的设计）：

| 代码 | 位置 | 不动原因 |
|------|------|----------|
| `scheduleFlush` 不取消 in-flight timer | `MessageEventHandler.kt:51-57` | commit `67e46011` 正确修复了 48ms 批量刷新饥饿 |
| `delta > 0` 条件 | `ChatMessageList.kt:311` | 只补偿增长是正确设计；Task 2 修复 key 后 shouldCompensate 能及时重置，不会卡死 |
| `ChatScreen.kt` 的 `LaunchedEffect(isScrollInProgress)` | `ChatScreen.kt:326` | commit `67e46011` 正确修复了 autoScroll-lock |
| `markdownAnimations(animateTextSize = { this })` | `MarkdownContent.kt:271` | beta.360 也有此行，非回归源 |
| `compensateState.lastHeight > 0` 守卫 | `ChatMessageList.kt:311` | 防御性编程，非回归源 |

---

## 附录 C：调试此问题的方法论

本次根因定位使用了以下方法，记录于此供未来参考：

1. **黄金参照对比**：用户指出 beta.360 稳定 → `git diff v2.0.0-beta.360 HEAD -- <file>` 精确定位
2. **commit 考古**：`git log --oneline v2.0.0-beta.360..HEAD -- <file>` 查看修改历史，理解每次改动的意图
3. **官方文档验证**：用 Context7 查 mikepenz 0.43.0 的 `rememberMarkdownState` API，确认 `immediate` 参数语义
4. **历史 commit message 交叉验证**：commit `731b954e` 的 message 描述了"701px↔760px oscillation"，与当前症状完全吻合，说明问题曾被发现但修复不完整

**教训**：当用户说"XX 版本是完美的"时，立即做 `git diff` 对比是最快的根因定位方法，比从头分析架构更高效。
