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

/**
 * DSH goal 投影域模型（session/projection 帧 key=goal / goal/change 事件全量值）。
 *
 * wire 形状（dsh-goal/lib/types/types.d.ts GoalProjection / GoalSnapshot /
 * GoalBlockReason；goals.d.ts GoalRef）：
 * ```
 * {goal: {id, revision, objective, phase, blockedReason?: {code, message}, maxGoalRounds},
 *  roundsStarted, createdAt, updatedAt}
 * ```
 * CAS 语义：goal.edit/pause/resume/complete/clear 的 ref{id,revision} 恒取当前投影
 * （mutateGoal 只认最新 revision，陈旧 ref → internal "stale goal ref"）。
 * phase 闭集：active|paused|blocked|complete（activation 是进程本地态，不上投影）。
 */
@Serializable
data class DshGoalBlockReason(
    val code: String = "",
    val message: String = "",
)

/** 目标快照（GoalSnapshot = GoalRef + objective/phase/maxGoalRounds；CAS ref 的宿主）。 */
@Serializable
data class DshGoalSnapshot(
    val id: String,
    val revision: Long = 0L,
    val objective: String = "",
    val phase: String = "active",
    val blockedReason: DshGoalBlockReason? = null,
    val maxGoalRounds: Long = 0L,
)

/** CAS 身份（goal 各 mutation 回执 value.ref；来源恒为当前投影，客户端不本地构造）。 */
@Serializable
data class DshGoalRef(
    val id: String,
    val revision: Long = 0L,
)

/**
 * 当前 goal 投影整值（last-wins 全量快照；goal/change clear 后与首建前恒 null）。
 * [DshGoalSnapshot.id]/[revision] 即下一个 mutation 的 CAS ref。
 */
@Serializable
data class DshGoalProjection(
    val goal: DshGoalSnapshot,
    val roundsStarted: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * DSH contextPressure 投影（session/projection 帧 key=contextPressure 的整值）。
 *
 * wire 形状（dsh-token-meter/lib/types/projection.d.ts ContextPressureProjection）：
 * ```
 * {pressureTokens?, projectedTokens?, contextWindow?}
 * ```
 * 三字段独立 last-wins（非同一请求的原子观测——参考值非计费/门控输入）。
 * Web 上下文环：分子 = projectedTokens ?? pressureTokens，分母 = contextWindow；
 * 两者任一缺席 → 整环不渲染（contextOccupancy 判据）。
 */
@Serializable
data class DshContextPressure(
    val pressureTokens: Long? = null,
    val projectedTokens: Long? = null,
    val contextWindow: Long? = null,
)

/**
 * DSH contextBreakdown 投影（session/projection 帧 key=contextBreakdown 的整值）。
 *
 * wire 形状（dsh-token-meter/lib/types/projection.d.ts ContextBreakdownProjection）：
 * ```
 * {systemTokens, toolsTokens, messageTokens}
 * ```
 * 启发式构成（固定密度估算，非 provider 锚定——不求合计等于 projectedTokens）。
 */
@Serializable
data class DshContextBreakdown(
    val systemTokens: Long = 0L,
    val toolsTokens: Long = 0L,
    val messageTokens: Long = 0L,
)

/**
 * DSH sessionStats 投影（session/projection 帧 key=sessionStats 的整值）。
 *
 * wire 形状（dsh-session-stats/lib/types/projection.d.ts SessionStatsTotals）：
 * ```
 * {turns, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens}
 * ```
 * 全日志累计（分页/压缩不变）；Web StatsLine 直接渲染。
 */
@Serializable
data class DshSessionStats(
    val turns: Long = 0L,
    val steps: Long = 0L,
    val llmMs: Long = 0L,
    val toolMs: Long = 0L,
    val ttftMs: Long = 0L,
    val ttftSteps: Long = 0L,
    val decodeMs: Long = 0L,
    val decodeTokens: Long = 0L,
)