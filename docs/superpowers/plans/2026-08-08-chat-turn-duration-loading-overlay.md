# Chat：turn 级时长统计 + 统一加载蒙版 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 assistant 回复气泡的统计栏计时覆盖整个 turn（首条消息创建 → 末条消息完成，流式中不重置），并在进入会话时用统一蒙版遮住消息区 + 输入栏直到消息与模型配置同时就绪（8s 超时兜底）。

**Architecture:** 两个独立改动。(1) `RenderableTurn` 新增 `turnStartMs`（turn 内首条 assistant 的 created），`durationMs` 改为 turn 级跨度；`MessageCardAssistant` 流式 ticker 改用 turn 起点。(2) `ModelConfigDelegate` 新增 `isLoaded` 完成标志，`ChatScreen` 计算 `shouldShowLoadingOverlay`（纯函数）并渲染 `ChatLoadingOverlay` 覆盖 content + bottomBar。

**Tech Stack:** Kotlin + Jetpack Compose + coroutines（`StateFlow`）+ JUnit 4（单测）。规范依据：`docs/superpowers/specs/2026-08-08-chat-turn-duration-loading-overlay-design.md`。

## Global Constraints

- **ChatScreen.kt 编辑协议**（docs/chatscreen-editing-protocol.md）：编辑前必须先 Read；每次编辑后运行 `.\gradlew :app:compileDevDebugKotlin`（120s 超时）；编译成功后立即提交；失败时 `git checkout -- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScreen.kt` 重新读取重试。禁止跨 agent 并行编辑 ChatScreen.kt。
- **所有路径在 Windows PowerShell**；命令超时：compileDevDebugKotlin 120s、testDevDebugUnitTest 180s。
- **不引入任何新 UI 依赖**；蒙版复用现有 `PulsingDotsIndicator`（`ui/components/indicators/PulsingDotsIndicator.kt`）。
- **无新 i18n 文案**（蒙版只有动画，无文字）；若出现硬编码字符串即违反规范。
- **新日志用 `AppLogger`**（本计划唯一日志点是 Task 2 中 loadProviders 现有 catch 块——保持原样，不新增日志）。
- 不修改 `version.properties`；不触碰消息渲染、SSE 管线、导航。
- 测试数据构造模式参照 `TurnGroupCalculatorTest.kt`（`Message.Assistant(id, sessionId, time=TimeInfo(created, completed), parentId, modelId)`）。

---

### Task 1: turn 级时长统计

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurn.kt:18-29, 76-79`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCardAssistant.kt:153-168`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurnTest.kt`

**Interfaces:**
- Consumes: `computeRenderableTurn(turnMessages: List<ChatMessage>?, currentMessage: ChatMessage, isTurnLast: Boolean, formatError: (Message.Assistant.ErrorInfo?) -> String?): RenderableTurn`（现有签名不变）；`ChatMessage(message, parts)`；`TimeInfo(created: Long, completed: Long?)`。
- Produces: `RenderableTurn` 新增只读字段 `turnStartMs: Long?`（turn 内首条 assistant 消息的 `time.created`）；`durationMs` 语义从"当前消息自身跨度"改为"turn 首条 created → turn 末条 completed"（仅当 turn 内所有 assistant 消息 completed 非空时非 null，否则 null 交给流式 ticker）。

- [ ] **Step 1: 写失败测试**（新建 `RenderableTurnTest.kt`）

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderableTurnTest {

    private fun assistantMsg(id: String, created: Long, completed: Long?) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = created, completed = completed),
            parentId = "",
            modelId = "test-model"
        ),
        parts = emptyList()
    )

    private fun compute(msgs: List<ChatMessage>): RenderableTurn =
        computeRenderableTurn(msgs, msgs.last(), true) { null }

    @Test
    fun `single completed message duration equals its own span`() {
        val t = compute(listOf(assistantMsg("a1", 1000L, 5000L)))
        assertEquals(4000L, t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `multi-message turn duration spans first created to last completed`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, 8000L))
        val t = compute(msgs)
        assertEquals(7000L, t.durationMs)      // 8000 - 1000
        assertEquals(1000L, t.turnStartMs)     // 首条 created，而非代表消息 a2 的 2500
    }

    @Test
    fun `streaming turn has null duration but stable turnStartMs`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, null))
        val t = compute(msgs)
        assertNull(t.durationMs)               // a2 未完成 → 交给流式 ticker
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `single streaming message has null duration`() {
        val t = compute(listOf(assistantMsg("a1", 1000L, null)))
        assertNull(t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurnTest"`（180s 超时）
Expected: FAIL —— `RenderableTurn` 无 `turnStartMs` 字段（编译错误）或 durationMs 断言不符（单消息场景 4000 通过，多消息 7000 得 5500）。

- [ ] **Step 3: 修改 `RenderableTurn.kt`**（两处）

字段（18-29 行 data class）：
```kotlin
@Immutable
data class RenderableTurn(
    val renderItems: List<RenderItem>,
    val isEmpty: Boolean,
    val errorText: String?,
    val agentName: String?,
    val modelId: String?,
    val durationMs: Long?,
    val turnStartMs: Long?,
    val stepFinishes: List<Part.StepFinish>,
    val taskAgentName: String?,
    val copyText: String?,
)
```

计算（替换 76-79 行的 durationMs 块）：
```kotlin
    // turn 起点 —— turn 内首条 assistant 消息的 created。
    // turn 分组只含 assistant 消息；minOf 比较时间戳不依赖列表顺序。
    val assistants = ordered.mapNotNull { it.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant }
    val turnStartMs: Long? = assistants.minOfOrNull { it.time.created }

    // 时长 —— turn 级跨度：首条 created → 末条 completed。
    // 仅当 turn 内所有 assistant 消息均 completed 时给值；任一仍流式 → null（流式 ticker 接管）。
    val completedTimes = assistants.mapNotNull { it.time.completed }
    val durationMs: Long? = if (turnStartMs != null && completedTimes.size == assistants.size) {
        completedTimes.max() - turnStartMs
    } else {
        null
    }
```

return 语句（101-111 行）加字段：
```kotlin
    return RenderableTurn(
        renderItems = renderItems,
        isEmpty = renderItems.isEmpty() && errorText == null,
        errorText = errorText,
        agentName = agentName,
        modelId = modelId,
        durationMs = durationMs,
        turnStartMs = turnStartMs,
        stepFinishes = stepFinishes,
        taskAgentName = taskAgentName,
        copyText = copyText,
    )
```

- [ ] **Step 4: 修改 `MessageCardAssistant.kt` 流式 ticker 起点**（164-168 行）

```kotlin
                    val displayDurationMs = if (isStreaming) {
                        // turn 级起点：turn 首条 assistant 的 created；fallback 当前消息 created。
                        // turn 内新消息出现时代表消息切换，但计时起点不变 → 不重置。
                        val start = renderableTurn.turnStartMs ?: assistantMsg?.time?.created
                        start?.let { nowMs - it } ?: 0L
                    } else {
                        durationMs ?: 0L
                    }
```

- [ ] **Step 5: 编译 + 单测**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）→ Expected: BUILD SUCCESSFUL
Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurnTest"`（180s）→ Expected: BUILD SUCCESSFUL（4 测试全过）

- [ ] **Step 6: 回归现有单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL（TurnGroupCalculatorTest、PartRenderLogicTest 等全部通过——确认无测试依赖旧的 per-message duration 语义）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurn.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCardAssistant.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurnTest.kt
git commit -m "feat: 统计栏计时改为 turn 级跨度（首条 created → 末条 completed，流式不重置）"
```

---

### Task 2: ModelConfigDelegate.isLoaded 完成标志

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ModelConfigDelegate.kt`（类字段区 + `loadProviders()` 213-226 行）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt:170` 附近

**Interfaces:**
- Consumes: 现有 `_allProviders` / `selectModelUseCase` / `scope` 字段。
- Produces: `ModelConfigDelegate.isLoaded: StateFlow<Boolean>`（初始 false，`loadProviders()` 的 finally 置 true）；`ChatViewModel.modelConfigLoaded: StateFlow<Boolean>` 委托给 `modelConfig.isLoaded`。Task 3 消费 `viewModel.modelConfigLoaded`。

- [ ] **Step 1: 加字段**（ModelConfigDelegate 类内，`_allProviders` 等 StateFlow 声明附近）

```kotlin
    private val _isLoaded = MutableStateFlow(false)

    /** Provider catalog 加载完成标志（成功或失败均置 true）。供 ChatScreen 加载蒙版消费。 */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()
```

- [ ] **Step 2: `loadProviders()` 的 try/catch 加 finally**

```kotlin
    fun loadProviders() {
        scope.launch {
            try {
                val response = selectModelUseCase.loadProviders(serverId)
                _allProviders.value = response.providers
                applyProviderFilter()
                _defaultModels.value = response.default
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Loaded ${response.providers.size} providers, defaults: ${response.default}")
                // 无需在此设置默认值，combine 块处理回退
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load providers", e)
            } finally {
                _isLoaded.value = true
            }
        }
    }
```

- [ ] **Step 3: ChatViewModel 暴露**

```kotlin
    val modelConfigState: StateFlow<ModelConfigState> get() = modelConfig.modelConfigState
    /** Provider catalog 加载完成标志（蒙版消费）。 */
    val modelConfigLoaded: StateFlow<Boolean> get() = modelConfig.isLoaded
```

- [ ] **Step 4: 编译 + 现有单测回归**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）→ Expected: BUILD SUCCESSFUL
Run: `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）→ Expected: BUILD SUCCESSFUL
（ModelConfigDelegate 无现有单测；isLoaded 行为由 Task 3 的纯函数测试 + 真机冒烟覆盖——不为此构造 12 路 combine 的 delegate 级测试。）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ModelConfigDelegate.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt
git commit -m "feat: ModelConfigDelegate 增加 isLoaded 加载完成标志（成功/失败均置位）"
```

---

### Task 3: 统一加载蒙版（ChatScreen）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/LoadingOverlayState.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatLoadingOverlay.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/LoadingOverlayStateTest.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScreen.kt`（约 282 行收集区、502 行 Scaffold 前、638-644 行 content Box、609-637 行 bottomBar）

**Interfaces:**
- Consumes: `viewModel.modelConfigLoaded`（Task 2）；`interaction.isLoading`（现有）；`sessionId`（ChatScreen 参数）；`PulsingDotsIndicator`（现有组件）。
- Produces: `internal fun shouldShowLoadingOverlay(modelReady: Boolean, messagesReady: Boolean, timeoutElapsed: Boolean): Boolean`；`@Composable fun ChatLoadingOverlay(modifier: Modifier = Modifier)`。

- [ ] **Step 1: 写失败测试**（新建 `LoadingOverlayStateTest.kt`）

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingOverlayStateTest {

    @Test
    fun `hidden when both ready`() {
        assertFalse(shouldShowLoadingOverlay(modelReady = true, messagesReady = true, timeoutElapsed = false))
    }

    @Test
    fun `shown when model config not ready`() {
        assertTrue(shouldShowLoadingOverlay(modelReady = false, messagesReady = true, timeoutElapsed = false))
    }

    @Test
    fun `shown when messages not ready`() {
        assertTrue(shouldShowLoadingOverlay(modelReady = true, messagesReady = false, timeoutElapsed = false))
    }

    @Test
    fun `hidden after timeout even if nothing ready`() {
        assertFalse(shouldShowLoadingOverlay(modelReady = false, messagesReady = false, timeoutElapsed = true))
    }

    @Test
    fun `hidden after timeout when partially ready`() {
        assertFalse(shouldShowLoadingOverlay(modelReady = false, messagesReady = true, timeoutElapsed = true))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.chat.util.LoadingOverlayStateTest"`（180s）
Expected: FAIL（编译错误：`shouldShowLoadingOverlay` 未定义）

- [ ] **Step 3: 创建纯函数**（`util/LoadingOverlayState.kt`）

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat.util

/**
 * 统一加载蒙版显示条件。
 *
 * 蒙版覆盖消息区 + 输入栏，直到模型配置（provider catalog 加载完成）与
 * 消息加载同时就绪；[timeoutElapsed]（8s 超时）后强制揭开，避免网络慢/失败
 * 导致蒙版永久挂着（此后各区域按自身状态渲染）。
 */
internal fun shouldShowLoadingOverlay(
    modelReady: Boolean,
    messagesReady: Boolean,
    timeoutElapsed: Boolean,
): Boolean = !(modelReady && messagesReady) && !timeoutElapsed
```

- [ ] **Step 4: 创建蒙版组件**（`components/ChatLoadingOverlay.kt`）

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator

/**
 * 统一加载蒙版 —— 不透明 surface + 居中 PulsingDots。
 * 覆盖消息区与输入栏（两处共用同一状态），揭蒙版后内容同时出现。
 */
@Composable
fun ChatLoadingOverlay(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            PulsingDotsIndicator()
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.chat.util.LoadingOverlayStateTest"`（180s）
Expected: BUILD SUCCESSFUL（5 测试全过）

- [ ] **Step 6: 先 Read ChatScreen.kt**（协议要求）

Read: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScreen.kt`（读取 260-310 与 490-660 段，确认行内容与计划一致）

- [ ] **Step 7: 修改 ChatScreen.kt**（4 处）

(a) import 区加：
```kotlin
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatLoadingOverlay
import dev.leonardo.ocbeacon.ui.screens.chat.util.shouldShowLoadingOverlay
```

(b) 282 行 `modelConfig` 收集附近加：
```kotlin
    val modelConfigLoaded by viewModel.modelConfigLoaded.collectAsStateWithLifecycle()
```

(c) Scaffold 前（502 行 `var showQuickNavigate` 附近）加蒙版状态：
```kotlin
    var showQuickNavigate by remember { mutableStateOf(false) }
    // 加载蒙版：模型配置 + 消息同时就绪才揭开；8s 超时兜底强制揭开。
    var overlayTimeout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8_000)
        overlayTimeout = true
    }
    val overlayVisible = shouldShowLoadingOverlay(
        modelReady = modelConfigLoaded,
        messagesReady = sessionId.isBlank() || !interaction.isLoading,
        timeoutElapsed = overlayTimeout,
    )
```

(d) content Box 内（645 行 `when {` 之后、else 分支之外，作为 Box 的最后一个子元素）：
```kotlin
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            when { ... 现有分支不动 ... }
            if (overlayVisible) {
                ChatLoadingOverlay(modifier = Modifier.fillMaxSize())
            }
        }
```

(e) bottomBar（609 行）包 Box：
```kotlin
        bottomBar = {
            Box {
                ChatScreenBottomBar(
                    ... 现有参数不动 ...
                )
                if (overlayVisible) {
                    ChatLoadingOverlay(modifier = Modifier.fillMaxSize())
                }
            }
        },
```

- [ ] **Step 8: 编译 + 单测**

Run: `.\gradlew :app:compileDevDebugKotlin`（120s）→ Expected: BUILD SUCCESSFUL（无警告）
Run: `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）→ Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/LoadingOverlayState.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatLoadingOverlay.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/LoadingOverlayStateTest.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScreen.kt
git commit -m "feat: 进入会话统一加载蒙版（消息+模型配置就绪后一次揭开，8s 超时兜底）"
```

---

### Task 4: 全量验证 + 真机冒烟

**Files:** 无代码改动。

- [ ] **Step 1: 完整构建**

Run: `.\gradlew :app:assembleDevDebug`（300s）
Expected: BUILD SUCCESSFUL（dev debug APK 产出）

- [ ] **Step 2: 真机冒烟**（replicant 工具，模拟器）

- 安装：`replicant_adb-app` operation=install，apkPath=`app/build/outputs/apk/dev/debug/app-dev-debug.apk`
- 启动：`replicant_adb-app` operation=launch，packageName=`dev.leonardo.ocbeacon.dev`
- 验证点（截图/UI dump）：
  1. 进入已有历史会话 → 蒙版（PulsingDots 居中、消息区与输入栏被 surface 覆盖）→ 就绪后一次揭开，消息 + 输入栏 agent/模型标签同时出现，无布局上抬
  2. 打开一个正在 SSE 输出的会话（或发送消息触发流式）→ 统计栏计时连续累加；turn 内出现多条 assistant 消息（agent 工具调用）时计时**不重置**
  3. 新建会话（TopBar "+" 或会话列表新建）→ 蒙版显示直到模型配置就绪 → 揭开后输入栏可用
- 崩溃检查：`replicant_adb-logcat` level=error，过滤 `FATAL|AndroidRuntime`

- [ ] **Step 3: 汇总验证结果**

输出：每个验证点的实际观察结果（截图路径/UI dump 结论 + logcat 状态）。任一失败 → 回到对应 Task 修复（ChatScreen.kt 修改需遵守编辑协议）。

---

## Self-Review

**Spec coverage:**
- Spec 设计 1（turn 级时长）→ Task 1 全部覆盖：turnStartMs、durationMs 语义、ticker 起点、显示位置不变。
- Spec 设计 2（统一蒙版）→ Task 2（isLoaded）+ Task 3（蒙版 UI/条件/超时）覆盖：不透明 Surface、8s 超时、新会话跳过消息等待（`sessionId.isBlank()`）、失败不阻塞（finally 置位 + 超时兜底）。
- Spec 测试计划 → Task 1/3 单测 + Task 4 全量/冒烟；androidTest 蒙版测试不新增（蒙版条件为纯函数已单测，UI 呈现由真机冒烟覆盖——避免为单一状态引入 Hilt Compose 测试基建）。

**Placeholder scan:** 无 TBD/TODO；所有代码块为完整可粘贴内容；Task 3 的 ChatScreen 修改标注了精确位置（行号 + 锚点代码）。

**Type consistency:**
- `turnStartMs: Long?` 在 Task 1 的 RenderableTurn 字段/计算/return/MessageCardAssistant 消费处一致。
- `isLoaded` → `modelConfigLoaded` 在 Task 2 定义、Task 3 (b) 消费处一致。
- `shouldShowLoadingOverlay(modelReady, messagesReady, timeoutElapsed)` 在 Task 3 Step 3 定义、Step 7(c) 调用、Step 1 测试调用处参数名一致。
- `ChatLoadingOverlay(modifier)` 在 Task 3 Step 4 定义、Step 7(d)(e) 调用处一致。
