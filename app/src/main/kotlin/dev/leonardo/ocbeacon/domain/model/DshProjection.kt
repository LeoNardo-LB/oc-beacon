package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * DSH tokenUsage 投影（session/projection 帧 key=tokenUsage 的整值）。
 *
 * wire 形状（dsh-openapi-notes.md §6；dsh-subagent/lib/types/projection-types.d.ts）：
 * ```
 * {uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}
 * ```
 * 四桶互斥累计（官方 tokenTotal 直接求和）。OpenCode 无此域 → Session.tokenUsage 恒 null。
 */
@Serializable
data class DshTokenUsage(
    val uncachedInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
) {
    /** 累计 tokens = 四桶求和（对齐官方 tokenTotal）。 */
    val total: Long get() = uncachedInputTokens + outputTokens + cacheReadTokens + cacheWriteTokens
}

/**
 * DSH subagentTiming 投影（session/projection 帧 key=subagentTiming 的整值）。
 *
 * wire 形状（dsh-subagent/lib/types/projection-types.d.ts:6-17）：
 * ```
 * {settledMs, active?: {since, through}}
 * ```
 * [active] 缺席 = 无开放 turn。active 内层展平为 [activeSince]/[activeThrough]（两字段同空）
 * ——简化序列化面，不失语义。
 */
@Serializable
data class DshSubagentTiming(
    val settledMs: Long = 0L,
    val activeSince: Long? = null,
    val activeThrough: Long? = null,
) {
    /**
     * 活跃时长派生（对齐官方 ui-subagent client.js:77-84 activityDuration 的
     * 非 running 分支）：无 active → settledMs；有 active → settledMs + max(0, through - since)。
     *
     * running 态官方取 now - since；此处用投影自带的 activeThrough（最近事件时刻
     * 折叠进投影切片的界）近似——帧驱动持续刷新，逼近 now，免弹窗内挂走时器。
     */
    val activeDurationMs: Long
        get() = settledMs + if (activeSince != null && activeThrough != null) {
            (activeThrough - activeSince).coerceAtLeast(0L)
        } else 0L
}
