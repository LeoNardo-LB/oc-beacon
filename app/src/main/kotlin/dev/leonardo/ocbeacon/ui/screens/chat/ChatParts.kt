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
