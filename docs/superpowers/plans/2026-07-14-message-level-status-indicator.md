# 消息级状态指示器实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会话繁忙状态从顶部进度条迁移到每条消息的统计栏，用 CircularProgressIndicator 区分用户消息（排队/等待回复）和 assistant 消息（回复中/已回复）。

**Architecture:** 新增 `UserMsgStatus` 枚举和纯函数推导逻辑，覆盖现有 `queuedMessageIds` 的盲区。UI 层在消息统计栏右对齐区域用 `AnimatedContent` 互换 QUEUED 徽章和圆环指示器。Assistant 统计栏扩展为流式期间也渲染精简 footer。

**Tech Stack:** Jetpack Compose, Material 3 (`CircularProgressIndicator`), Kotlin, JUnit 4 + MockK

## Global Constraints

- Material 3 优先：使用原生 `CircularProgressIndicator`，不自定义 Canvas 绘制
- Theme tokens：颜色用 `MaterialTheme.colorScheme` 语义色，动画用 `AppMotion` 常量
- Alpha tokens：透明度用 `AlphaTokens`，不硬编码
- Shape tokens：形状用 `ShapeTokens`
- 编译验证：`.\gradlew :app:compileDevDebugKotlin`（120s 超时）
- 单元测试：`.\gradlew :app:testDevDebugUnitTest --rerun`（180s 超时）
- ChatScreen.kt 编辑协议：Read before Edit，每次编辑后编译检查
- 路径处理：远端路径用 `PathUtils`

---

### Task 1: 创建 UserMsgStatus 枚举 + 推导函数

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/octether/domain/model/UserMsgStatus.kt`
- Create: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/util/UserMsgStatusCalculator.kt`
- Test: `app/src/test/kotlin/dev/leonardo/octether/ui/screens/chat/util/UserMsgStatusCalculatorTest.kt`

**Interfaces:**
- Consumes: `SessionStatus`（`domain/model/SessionStatus.kt`），`Message`（`domain/model/Message.kt`）
- Produces: `UserMsgStatus` 枚举，`calculateUserMsgStatus()` 纯函数

- [ ] **Step 1: 创建 UserMsgStatus 枚举**

```kotlin
// app/src/main/kotlin/dev/leonardo/octether/domain/model/UserMsgStatus.kt
package dev.leonardo.octether.domain.model

/**
 * User message status for the per-message status indicator.
 * Derived from FSM status + message list position.
 */
enum class UserMsgStatus {
    /** Message is queued behind a pending assistant (shows QUEUED badge) */
    Queued,

    /** Session is busy, message confirmed but assistant hasn't completed reply (shows spinning circle) */
    Waiting,

    /** Assistant has completed reply or session is idle (no indicator) */
    Completed,
}
```

- [ ] **Step 2: 创建推导函数**

```kotlin
// app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/util/UserMsgStatusCalculator.kt
package dev.leonardo.octether.ui.screens.chat.util

import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.SessionStatus
import dev.leonardo.octether.domain.model.UserMsgStatus

/**
 * Derives the status of a user message for the per-message indicator.
 *
 * Covers the QUEUED gap: when FSM is Busy but no pending assistant exists yet
 * (first message, assistant hasn't created a message), the message is "Waiting"
 * rather than falling through to "Completed".
 *
 * @param messageId The user message ID to check
 * @param queuedMessageIds Set of queued message IDs (from MessageDataDelegate)
 * @param fsmStatus Current FSM session status
 * @param messages Full message list (ordered, oldest first)
 * @return The user message status
 */
fun calculateUserMsgStatus(
    messageId: String,
    queuedMessageIds: Set<String>,
    fsmStatus: SessionStatus,
    messages: List<Message>,
): UserMsgStatus = when {
    messageId in queuedMessageIds -> UserMsgStatus.Queued
    fsmStatus is SessionStatus.Busy -> {
        val messageIndex = messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) return UserMsgStatus.Completed

        val nextAssistant = messages.drop(messageIndex + 1)
            .firstOrNull { it is Message.Assistant } as? Message.Assistant

        if (nextAssistant?.time?.completed == null) UserMsgStatus.Waiting
        else UserMsgStatus.Completed
    }
    else -> UserMsgStatus.Completed
}

/**
 * Batch-compute user message statuses for all user messages in the list.
 */
fun calculateAllUserMsgStatuses(
    messages: List<Message>,
    queuedMessageIds: Set<String>,
    fsmStatus: SessionStatus,
): Map<String, UserMsgStatus> {
    return messages
        .filterIsInstance<Message.User>()
        .associate { msg ->
            msg.id to calculateUserMsgStatus(msg.id, queuedMessageIds, fsmStatus, messages)
        }
}
```

- [ ] **Step 3: 编写单元测试**

```kotlin
// app/src/test/kotlin/dev/leonardo/octether/ui/screens/chat/util/UserMsgStatusCalculatorTest.kt
package dev.leonardo.octether.ui.screens.chat.util

import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.SessionStatus
import dev.leonardo.octether.domain.model.TimeInfo
import dev.leonardo.octether.domain.model.UserMsgStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMsgStatusCalculatorTest {

    private fun userMsg(id: String, created: Long = 1000L) = Message.User(
        id = id,
        sessionId = "sess",
        time = TimeInfo(created = created),
    )

    private fun assistantMsg(id: String, completed: Long? = null, created: Long = 2000L) = Message.Assistant(
        id = id,
        sessionId = "sess",
        time = TimeInfo(created = created, completed = completed),
        parentId = "parent",
    )

    @Test
    fun `queued message returns Queued`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1"), userMsg("u2"))
        val result = calculateUserMsgStatus("u2", setOf("u2"), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Queued, result)
    }

    @Test
    fun `Busy with no next assistant returns Waiting (covers gap)`() {
        val messages = listOf(userMsg("u1"))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Waiting, result)
    }

    @Test
    fun `Busy with next assistant completed returns Completed`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = 3000L))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `Busy with next assistant not completed returns Waiting`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = null))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Waiting, result)
    }

    @Test
    fun `Idle returns Completed regardless of messages`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = null))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Idle, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `Retry returns Completed (independent from circle)`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = null))
        val retry = SessionStatus.Retry(attempt = 1, message = "error", next = 9999L)
        val result = calculateUserMsgStatus("u1", emptySet(), retry, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `message not in list returns Completed`() {
        val messages = listOf(userMsg("u1"))
        val result = calculateUserMsgStatus("unknown", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `calculateAll computes statuses for all user messages`() {
        val messages = listOf(
            userMsg("u1"),
            assistantMsg("a1", completed = 3000L),
            userMsg("u2"),
            assistantMsg("a2", completed = null),
            userMsg("u3"),
        )
        val result = calculateAllUserMsgStatuses(messages, setOf("u3"), SessionStatus.Busy)
        assertEquals(UserMsgStatus.Completed, result["u1"])
        assertEquals(UserMsgStatus.Waiting, result["u2"])
        assertEquals(UserMsgStatus.Queued, result["u3"])
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*UserMsgStatusCalculatorTest" --rerun`
Expected: PASS (8 tests)

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/domain/model/UserMsgStatus.kt app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/util/UserMsgStatusCalculator.kt app/src/test/kotlin/dev/leonardo/octether/ui/screens/chat/util/UserMsgStatusCalculatorTest.kt
git commit -m "feat: add UserMsgStatus enum and derivation logic"
```

---

### Task 2: 将 userMsgStatuses 接入 MessageListState

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/ChatViewModel.kt:56-64` (MessageListState)
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/MessageDataDelegate.kt:174-216`
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/ChatViewModel.kt:630-645` (combine output)

**Interfaces:**
- Consumes: `calculateAllUserMsgStatuses()` from Task 1, `SessionStatus` from FSM, `queuedMessageIds`, `visible` message list
- Produces: `MessageListState.userMsgStatuses: Map<String, UserMsgStatus>`

- [ ] **Step 1: 在 MessageListState 中添加 userMsgStatuses 字段**

在 `ChatViewModel.kt:62`（`queuedMessageIds` 行之后）添加：

```kotlin
@Immutable
data class MessageListState(
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val queuedMessageIds: Set<String> = emptySet(),
    val pendingMessageIds: Set<String> = emptySet(),
    val userMsgStatuses: Map<String, UserMsgStatus> = emptyMap(),
)
```

同时在文件顶部添加 import：
```kotlin
import dev.leonardo.octether.domain.model.UserMsgStatus
```

- [ ] **Step 2: 在 MessageDataDelegate 中计算 userMsgStatuses**

在 `MessageDataDelegate.kt` 的 `messageListState` combine 中，在 `queuedMessageIds` 计算之后（约 L192）、`chatMessages` 映射之前（约 L199）添加：

```kotlin
            // Compute user message statuses for per-message indicator
            val userMsgStatuses = calculateAllUserMsgStatuses(
                messages = visible,
                queuedMessageIds = queuedMessageIds,
                fsmStatus = fsmStatus,
            )
```

然后在 `MessageListState` 构造中（约 L208-216）添加：

```kotlin
            val state = MessageListState(
                messages = chatMessages,
                messageCount = chatMessages.size,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlder = isLoadingOlder,
                toolExpandedStates = toolExpandedStates,
                queuedMessageIds = queuedMessageIds,
                pendingMessageIds = pendingMessageIds,
                userMsgStatuses = userMsgStatuses,
            )
```

同时在文件顶部添加 import：
```kotlin
import dev.leonardo.octether.ui.screens.chat.util.calculateAllUserMsgStatuses
```

- [ ] **Step 3: 确保 ChatViewModel 中透传 userMsgStatuses**

检查 `ChatViewModel.kt` 约 L630-645，确认 `messageListState` 的 combine 输出已包含新字段。如果 `MessageListState` 构造在此处有手动字段映射，添加 `userMsgStatuses = msgList.userMsgStatuses`。

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 运行现有 queued 测试确认无回归**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*QueuedTest" --rerun`
Expected: PASS (所有 queued 测试通过)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/MessageDataDelegate.kt
git commit -m "feat: wire userMsgStatuses into MessageListState"
```

---

### Task 3: 移除顶部进度条

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/ChatScreen.kt:638-646`

- [ ] **Step 1: 读取 ChatScreen.kt 确认当前代码**

Run: Read `ChatScreen.kt` offset=636 limit=15

确认以下代码存在：
```kotlin
                    // Indeterminate progress bar under the top bar when busy
                    val isBusy = sessionMeta.sessionStatus is SessionStatus.Busy || sessionMeta.sessionStatus is SessionStatus.Retry
                    AnimatedVisibility(
                        visible = isBusy,
                        enter = fadeIn(animationSpec = tween(AppMotion.SHORT)),
                        exit = fadeOut(animationSpec = tween(AppMotion.SHORT))
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
```

- [ ] **Step 2: 删除顶部进度条代码块**

将 L638-646 整段替换为空（删除注释 + isBusy 变量 + AnimatedVisibility + LinearProgressIndicator）。

- [ ] **Step 3: 清理未使用的 import（如有）**

检查 `LinearProgressIndicator`、`AnimatedVisibility`、`fadeIn`、`fadeOut`、`tween`、`AppMotion` 是否在文件其他位置使用。如果仅此处使用则删除对应 import。`SessionStatus` 在文件其他位置有使用，保留。

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: remove top bar busy progress indicator"
```

---

### Task 4: 更新 MessageCard + MessageCardUser + ChatMessageList

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/MessageCard.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/MessageCardUser.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt:564-567`

**Interfaces:**
- Consumes: `UserMsgStatus` from Task 1, `MessageListState.userMsgStatuses` from Task 2
- Produces: 更新后的 MessageCardUser 组件，QUEUED 徽章右对齐 + 圆环互换

- [ ] **Step 1: 更新 MessageCard 参数**

将 `MessageCard.kt` 中的 `isQueued: Boolean = false` 替换为 `userMsgStatus: UserMsgStatus = UserMsgStatus.Completed`：

```kotlin
// MessageCard.kt
package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.runtime.Composable
import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.domain.model.UserMsgStatus
import dev.leonardo.octether.ui.screens.chat.ChatMessage
import dev.leonardo.octether.ui.screens.chat.tools.RenderableTurn

enum class MessageCardRole { USER, ASSISTANT }

@Composable
internal fun MessageCard(
    role: MessageCardRole,
    currentMessage: ChatMessage,
    userMsgStatus: UserMsgStatus = UserMsgStatus.Completed,
    renderableTurn: RenderableTurn? = null,
    onViewSubSession: ((String) -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    isAmoled: Boolean = false,
    isTurnLast: Boolean = false,
    agents: List<AgentInfo> = emptyList(),
) {
    when (role) {
        MessageCardRole.USER -> MessageCardUser(
            currentMessage = currentMessage,
            userMsgStatus = userMsgStatus,
            onRevert = onRevert,
            onCopyText = onCopyText,
            isAmoled = isAmoled,
        )
        MessageCardRole.ASSISTANT -> MessageCardAssistant(
            renderableTurn = renderableTurn ?: error("renderableTurn is required for ASSISTANT role"),
            currentMessage = currentMessage,
            onViewSubSession = onViewSubSession,
            onOpenFile = onOpenFile,
            isAmoled = isAmoled,
            isTurnLast = isTurnLast,
            agents = agents,
        )
    }
}
```

- [ ] **Step 2: 更新 MessageCardUser — 参数 + QUEUED 右移 + 圆环**

修改 `MessageCardUser.kt`：

1. 将 `isQueued: Boolean` 参数替换为 `userMsgStatus: UserMsgStatus`
2. 添加必要的 import
3. 在统计栏 Row 中，将 QUEUED 徽章从左侧（时间之后）移除
4. 在 Spacer(weight=1f) 之后、Undo 按钮之前，添加状态指示器区域

统计栏 Row 修改后的结构：

```kotlin
                    // 统计栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：时间
                        val timeText = remember(currentMessage.message.time.created) {
                            SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(currentMessage.message.time.created))
                        }
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                        )

                        // 弹性空白
                        Spacer(modifier = Modifier.weight(1f))

                        // 右侧：状态指示器（QUEUED 徽章 与 圆环 互换）
                        AnimatedContent(
                            targetState = userMsgStatus,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(AppMotion.SHORT)) togetherWith
                                fadeOut(animationSpec = tween(AppMotion.SHORT))
                            },
                            label = "user_msg_status"
                        ) { status ->
                            when (status) {
                                UserMsgStatus.Queued -> {
                                    Surface(
                                        shape = ShapeTokens.extraSmall,
                                        color = QueuedBadgeColor
                                    ) {
                                        Text(
                                            text = stringResource(R.string.chat_queued),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 8.sp,
                                                color = QueuedBadgeTextColor
                                            ),
                                            modifier = Modifier.padding(horizontal = SpacingTokens.XS.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                UserMsgStatus.Waiting -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                                UserMsgStatus.Completed -> { /* nothing */ }
                            }
                        }

                        // Undo 按钮
                        if (onRevert != null) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.chat_revert),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        performHaptic(hapticView, hapticOn)
                                        showRevertConfirmation = true
                                    },
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }

                        // Copy 按钮
                        if (onCopyText != null) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        performHaptic(hapticView, hapticOn)
                                        onCopyText()
                                    },
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                    }
```

需要添加的 import（文件顶部）：
```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import dev.leonardo.octether.domain.model.UserMsgStatus
import dev.leonardo.octether.ui.theme.AppMotion
```

- [ ] **Step 3: 更新 ChatMessageList 传递 userMsgStatus**

在 `ChatMessageList.kt:564-567`，将 `isQueued = chatMessage.message.id in messageState.queuedMessageIds` 替换为：

```kotlin
                                MessageCard(
                                    role = MessageCardRole.USER,
                                    currentMessage = chatMessage,
                                    userMsgStatus = messageState.userMsgStatuses[chatMessage.message.id] ?: UserMsgStatus.Completed,
```

添加 import：
```kotlin
import dev.leonardo.octether.domain.model.UserMsgStatus
```

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/MessageCard.kt app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/MessageCardUser.kt app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/ChatMessageList.kt
git commit -m "feat: move QUEUED badge to right + add waiting circle indicator on user messages"
```

---

### Task 5: 更新 MessageCardAssistant — 流式 footer + 移除 token 统计 + 圆环

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/MessageCardAssistant.kt`

- [ ] **Step 1: 读取 MessageCardAssistant.kt 确认当前代码**

Run: Read `MessageCardAssistant.kt` 全文

确认：
- footer 渲染条件在 `if (stepFinishes.isNotEmpty())` 块内（约 L175-260）
- token 统计文本在约 L240-247：`"↑${formatTokenCount(totalInput)} ↓${formatTokenCount(totalOutput)}"`
- 有多个 fallback footer 块（约 L260-310）

- [ ] **Step 2: 添加 isStreaming 判断**

在函数顶部（约 L73，`assistantMsg` 赋值后）添加：

```kotlin
    val isStreaming = assistantMsg?.time?.completed == null
```

- [ ] **Step 3: 在 footer 逻辑前添加流式精简 footer**

在 `// Token/cost/duration footer — only on the last message of a turn` 注释之前（约 L175），添加流式 footer：

```kotlin
                // Streaming footer — minimal: time + agent + spinning circle
                if (isStreaming && isTurnLast) {
                    Spacer(modifier = Modifier.height(if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        assistantMsg?.time?.created?.let { createdMs ->
                            val timeText = remember(createdMs) {
                                SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(Date(createdMs))
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                        if (!agentName.isNullOrBlank()) {
                            val tagColor = agentColor(agentName, agents)
                            Surface(
                                shape = ShapeTokens.smallMedium,
                                color = tagColor.copy(alpha = AlphaTokens.FAINT)
                            ) {
                                Text(
                                    text = agentName.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = tagColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
```

- [ ] **Step 4: 移除 token 统计文本**

在已完成 footer 中（`stepFinishes.isNotEmpty()` 块内），删除以下代码：

```kotlin
                            if (totalInput > 0 || totalOutput > 0) {
                                Text(
                                    text = "↑${formatTokenCount(totalInput)} ↓${formatTokenCount(totalOutput)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
```

同时可以移除 `totalInput`、`totalOutput`、`hasTokenStats` 变量（如果不再使用），以及 `formatTokenCount` import（如果仅此处使用）。保留 `lastTokens` 如果 `hasFooter` 判断仍需要它——但移除 token 统计后，`hasTokenStats` 恒为 false，可从 `hasFooter` 条件中移除：

```kotlin
                    val hasFooter = (durationMs ?: 0) > 0 || !modelId.isNullOrBlank() || !agentName.isNullOrBlank()
```

- [ ] **Step 5: 添加 CircularProgressIndicator import**

在文件顶部添加：
```kotlin
import androidx.compose.material3.CircularProgressIndicator
```

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 运行现有测试确认无回归**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: PASS（所有测试通过）

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/octether/ui/screens/chat/components/MessageCardAssistant.kt
git commit -m "feat: add streaming footer with circle indicator + remove token stats from assistant card"
```

---

### Task 6: 全量编译 + 测试验证

- [ ] **Step 1: 全量编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 全量单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: PASS（所有测试通过）

- [ ] **Step 3: 构建 APK 验证**

Run: `.\gradlew :app:assembleDevRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 最终 Commit（如有遗漏的清理）**

检查 `git status`，如有未提交的清理（如未使用的 import），提交：
```bash
git add -A
git commit -m "chore: cleanup unused imports after status indicator refactor"
```
