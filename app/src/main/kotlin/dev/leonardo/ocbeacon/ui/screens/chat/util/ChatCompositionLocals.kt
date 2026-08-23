package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver

// ============ 通过 CompositionLocal 传递的聊天设置 ============

/** 工具卡片是否默认折叠。 */
val LocalCollapseTools = compositionLocalOf { false }

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

/** 已持久化的工具卡片展开/折叠状态，以 Part.Tool.id 或 Part.Patch.id 为键。 */
val LocalToolExpandedStates = compositionLocalOf<Map<String, Boolean>> { emptyMap() }

/** 通过 part id 切换工具卡片展开状态的回调。 */
val LocalOnToggleToolExpanded = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

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
