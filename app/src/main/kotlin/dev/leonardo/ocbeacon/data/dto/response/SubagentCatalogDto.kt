package dev.leonardo.ocbeacon.data.dto.response

/**
 * DSH subagent.list 目录行 DTO（AgentSheet 多级树权威域）。
 *
 * 形状（docs/api/dsh-openapi.yaml SubagentListEntry + 2026-09-25 活体实录）：
 * `{"kind":"child","id":…,"mode":"continuable","label":…,"activity":"running",
 * "hasChildren":true}`；diagnostic 变体仅 kind/id/reason（corrupt|unsupported|
 * unavailable）。one-shot 的 label 可选；id 为裸会话 id（无 session- 前缀），
 * 可继续作为下一层 parentSessionId（L2 懒加载实证）。
 */
data class SubagentListEntryDto(
    val kind: String,
    val id: String,
    val mode: String? = null,
    val activity: String? = null,
    val hasChildren: Boolean = false,
    val label: String? = null,
    val reason: String? = null,
)
