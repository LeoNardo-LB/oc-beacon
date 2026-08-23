package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Part

/**
 * 用于过滤和分类聊天 parts 的工具函数。
 */

/**
 * 判断一个 Part 是否应在聊天气泡内渲染。
 * 不可渲染的 parts（StepStart、StepFinish、Snapshot、Subtask、Compaction、
 * Agent、SessionTurn、Unknown）在显示前被过滤掉。
 */
internal fun isBubbleRenderablePart(part: Part): Boolean {
    return when (part) {
        is Part.Text,
        is Part.Reasoning,
        is Part.Patch,
        is Part.File,
        is Part.Permission,
        is Part.Question,
        is Part.Abort,
        is Part.Retry,
        is Part.Tool -> true
        else -> false
    }
}

/**
 * #207：思考卡计时器是否应持续走动的判定（三态合成）。
 *
 * - partEnded（time.end 存在）→ 永不计时（历史完结态）。
 * - 未结束且有锚（time.start > 0——SSE started / REST in-flight 快照 created）→ 计时。
 * - 未结束且无锚：仅会话流式中计时（活体重进错过 started 的续计语义，2026-08-16）；
 *   会话 idle（历史残留 time=null——野生实例：事故恢复消息 reasoning part）→ 静态不计时。
 *
 * 原判定 `!partEnded || (sessionStreaming && !partEnded)` 对 time=null 恒真 →
 * 永续 tick，且计时锚点回退到组合期时钟（ReasoningBlock fallback）→
 * LazyColumn 滑出销毁、滑回重建即归零（用户报告：上下滑动计时反复从 0 开始）。
 */
internal fun isReasoningStreaming(
    partEnded: Boolean,
    sessionStreaming: Boolean,
    hasValidAnchor: Boolean,
): Boolean = !partEnded && (hasValidAnchor || sessionStreaming)

/**
 * 过滤 Parts 列表，仅包含可渲染的，
 * 保留服务器返回的原始顺序。
 *
 * 这是已修复的核心逻辑：此前 parts 被拆分为
 * contentParts 和 stepParts 组并乱序渲染。现在保留
 * 原始交错（Text → Tool → Reasoning → Tool → Text）。
 */
internal fun filterRenderableParts(parts: List<Part>): List<Part> {
    return parts.filter(::isBubbleRenderablePart)
}
