package dev.leonardo.ocbeacon.domain.model

/**
 * 子代理目录行（AgentSheet 多级树，2026-09 树化）——DSH subagent.list 权威行与
 * OpenCode 本地镜像行的统一投影。
 *
 * - DSH（subagent.list SubagentListEntry）：label=委派描述（one-shot 可缺），
 *   activity="running"→[isRunning]，hasChildren 驱动展开箭头；kind=diagnostic 行
 *   （corrupt/unsupported/unavailable）灰显不可点（官方同款）。
 * - OpenCode V1/V2（本地 session 镜像）：label=title，isRunning=FSM Busy 或
 *   active 轮询命中；hasChildren 由镜像子代数推导。
 * - mode（one-shot|continuable）DTO 保留字段——MVP 不展示。
 */
data class SubagentChild(
    val sessionId: String,
    val label: String? = null,
    val isRunning: Boolean = false,
    val hasChildren: Boolean = false,
    /** DSH kind=diagnostic 行：灰显、不可点、无展开箭头。 */
    val isDiagnostic: Boolean = false,
    /** diagnostic 原因：corrupt / unsupported / unavailable。 */
    val diagnosticReason: String? = null,
    /** one-shot | continuable（DSH；MVP 不展示，字段保真）。 */
    val mode: String? = null,
)
