package dev.leonardo.ocbeacon.domain.model

/**
 * DSH 子智能体目录行（subagents/list 权威域整帧行，backlog #310① 续聊底座）。
 *
 * wire 形状（docs/research/2026-09-05-310-wire-contracts.md §①；
 * dsh-subagent SubagentListEntry zod schema）：
 * `{"kind":"child","id":…,"mode":"continuable"|"one-shot","label":…,
 * "activity":"running"|"inactive","hasChildren":true}`；diagnostic 变体仅
 * kind/id/reason（corrupt|unsupported|unavailable）——kind 判别。
 *
 * 与 [SubagentChild]（AgentSheet 树 UI 投影）分工：本行是 #310① 续聊数据层
 * 的原样域保真（mode 驱动 one-shot composer 禁用、activity 驱动运行角标，
 * AgentSheet 刷新迭代接线）。
 */
data class SubagentCatalogEntry(
    /** child | diagnostic（[KIND_CHILD]/[KIND_DIAGNOSTIC]）。 */
    val kind: String,
    /** 裸会话 id（无 session- 前缀）——可继续作为下一层 parentSessionId（L2 懒加载）。 */
    val id: String,
    /** running | inactive（child 行；diagnostic 行 null）。 */
    val activity: String? = null,
    val hasChildren: Boolean = false,
    /** one-shot | continuable（child 行；续聊发送仅 continuable，diagnostic 行 null）。 */
    val mode: String? = null,
    /** 委派描述（one-shot 可缺）。 */
    val label: String? = null,
    /** diagnostic 行原因：corrupt / unsupported / unavailable。 */
    val reason: String? = null,
) {
    val isChild: Boolean get() = kind == KIND_CHILD
    val isDiagnostic: Boolean get() = kind == KIND_DIAGNOSTIC
    val isRunning: Boolean get() = activity == ACTIVITY_RUNNING
    /** 续聊候选行（one-shot 不可续聊——官方 composer 禁用同款判据）。 */
    val isContinuable: Boolean get() = mode == MODE_CONTINUABLE

    companion object {
        const val KIND_CHILD = "child"
        const val KIND_DIAGNOSTIC = "diagnostic"
        const val ACTIVITY_RUNNING = "running"
        const val ACTIVITY_INACTIVE = "inactive"
        const val MODE_CONTINUABLE = "continuable"
        const val MODE_ONE_SHOT = "one-shot"
    }
}

/**
 * subagents/list 整帧（#310①）：entries + parentAvailable。
 *
 * [parentAvailable]=false 表示父 Agent 不在线——此时 subagents/prompt 将被
 * subagent/parent-unavailable 拒绝（服务端 agent.followup 前置校验），UI 侧
 * 据此禁发/提示（interruptByParent 不受限——durable 父址中断）。
 */
data class SubagentCatalog(
    val entries: List<SubagentCatalogEntry>,
    val parentAvailable: Boolean,
)
