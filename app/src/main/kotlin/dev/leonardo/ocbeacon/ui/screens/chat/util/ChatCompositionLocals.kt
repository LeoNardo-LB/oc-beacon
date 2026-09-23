package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ============ 通过 CompositionLocal 传递的聊天设置 ============

/** 工具卡片是否默认自动展开（#202 改名自 LocalCollapseTools；true=展开，与存储值同向）。 */
val LocalAutoExpandTools = compositionLocalOf { false }

/** reasoning 块是否默认展开。 */
val LocalExpandReasoning = compositionLocalOf { false }

/** 是否在同一轮次的消息间显示分隔线。 */
val LocalShowTurnDividers = compositionLocalOf { true }

/** 是否启用触觉反馈。 */
val LocalHapticFeedbackEnabled = compositionLocalOf { true }

/**
 * 当前会话是否正在活跃流式传输（FSM activity = Streaming）。
 * reasoning 计时器的权威控制；与 per-part `time.end == null` 组合，
 * 使只有当前 reasoning part 显示计时器（方案 B）。
 */
val LocalSessionStreaming = staticCompositionLocalOf { false }

/** 图片保存请求回调，供图片预览 composable 使用。 */
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }

/**
 * 已持久化的工具卡片展开/折叠状态，以 Part.Tool.id 或 Part.Patch.id 为键。
 *
 * #429 L0：以 **StateFlow 整体**下沉（稳定身份）——原 Map 直供时，任一键
 * 翻转=新 Map 实例=local 值变化=**全部读者**重组（真机实测 toggle 一次全列表
 * 重组+GC 122MB）。读者经 [toolExpandedOrDefault] 做 per-key 派生读取，
 * 只在自己键的值翻转时重组。
 */
val LocalToolExpandedStates = compositionLocalOf<StateFlow<Map<String, Boolean>>> {
    MutableStateFlow(emptyMap())
}

/**
 * #429 L0：逐键展开态读取——collectAsState 订阅不读值（不触发本层重组），
 * derivedStateOf 只在**本键**的布尔值翻转时使读者失效。
 */
@androidx.compose.runtime.Composable
internal fun toolExpandedOrDefault(key: String, default: Boolean = false): Boolean {
    val states: State<Map<String, Boolean>> = LocalToolExpandedStates.current.collectAsState()
    return remember(key) { derivedStateOf { states.value[key] ?: default } }.value
}

/** 通过 part id 切换工具卡片展开状态的回调。 */
val LocalOnToggleToolExpanded = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/**
 * #429：步组「展开计算期」共享表（快照态，键=stepGroupStateKey）。
 * 引擎信号由卡体内的 StepGroupCard 写入；**外层** #sgh 折叠行条目（大组懒
 * 加载路径，与卡体不同 LazyItem）经本表读取显示 spinner——跨条目直连无路径
 * （LocalFoldRowYReport 同款模式）。
 */
val LocalStepGroupComputing = compositionLocalOf<androidx.compose.runtime.MutableState<Map<String, Boolean>>> {
    androidx.compose.runtime.mutableStateOf(emptyMap())
}

/** 工具特定卡片 composable 的解析器。 */
val LocalToolCardResolver = compositionLocalOf<ToolCardResolver> {
    DefaultToolCardResolver()
}

/** 以 sessionId 为键的文件 diff。支撑 [dev.leonardo.ocbeacon.domain.model.Part.Patch] 行数统计。 */
val LocalSessionDiffs = compositionLocalOf<Map<String, List<FileDiff>>> { emptyMap() }

/**
 * #182（2026-08-21）：Task 工具卡片展开时的全量输出拉取器。
 * 策略（grilling Q13 定案）：part 优先（重拉父会话消息按 part id 取服务器
 * 全量 output）→ part 截断/缺失时降级子智能体会话 transcript。DB 仍存 500 字符
 * 预览（#79 体积目标不变）。返回 null = 两路均未取到（卡片用本地预览）。
 */
val LocalTaskOutputFetcher = compositionLocalOf<(suspend (partId: String, subSessionId: String?) -> String?)?> { null }
