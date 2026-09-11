package dev.leonardo.ocbeacon.data.api.dsh

/**
 * DSH 按代事件词汇表（#391 切片7；spec Implementation Decisions §172-174
 * 「事件映射重构为『共享处理器 + 按代词汇表』」）。
 *
 * 分工：
 * - **共享处理器** = [DshEventMapper] 的 SessionEvent `when`，只承载**有转录
 *   语义**的类型映射（与世代无关）；
 * - **按代词汇表** = 本类，声明每一代**已知但无需映射**的类型 → 具名忽略原因。
 *
 * 词汇表之外的类型统一 [DshIgnoreReason.UNKNOWN_DEGRADED] + 日志遥测（容错优先，
 * 绝不拒绝重建）；仅结构性违约（乱序 / 种子缺失 / surfaceOp 越界）才拒绝重建。
 * 新增一代 = 增一个词汇表实例，共享处理器零改动。
 *
 * 键控维度 = **会话格式版本**（session 头 `version`）。V3（DSH 0.1.5-rc.1）是 V2
 * 的超集：V3 新增类型里 system/message、deliverables/presented、tool/ptc-dispatch*
 * 有转录映射（不入本表），assistant/attempt、feedback/message-put|delete、
 * subagent/catalog 为已知忽略（入 V3 表）。
 */
class DshEventVocabulary private constructor(
    /** 会话格式版本标签（日志/遥测归因用）。 */
    val version: String,
    private val ignorable: Map<String, String>,
) {
    /** 该代已知且无需映射的类型 → 忽略原因；非本代词汇（未知）返回 null。 */
    fun ignoreReason(type: String): String? = ignorable[type]

    companion object {
        /** 会话格式 V1/V2（DSH ≤0.1.4）。 */
        val V2: DshEventVocabulary = DshEventVocabulary("v2", V2_IGNORABLE)

        /** 会话格式 V3（DSH 0.1.5-rc.1）。 */
        val V3: DshEventVocabulary = DshEventVocabulary("v3", V2_IGNORABLE + V3_IGNORABLE)

        /** 当前默认（实况帧面无法预知会话格式时择取最新）。 */
        val CURRENT: DshEventVocabulary = V3

        /** 会话头 version → 词汇表；未知/缺席回退 [CURRENT]（容错优先）。 */
        fun ofSessionFormatVersion(version: Long?): DshEventVocabulary = when (version) {
            1L, 2L -> V2
            3L -> V3
            else -> CURRENT
        }
    }
}

/** V1/V2 已知忽略词汇（原 [DshEventMapper] 内联分支的声明式收编，语义逐条等价）。 */
private val V2_IGNORABLE: Map<String, String> = mapOf(
    // Tier2 会话子节点 / preamble 策略态 / 生命周期噪声
    "subagent/descriptor" to DshIgnoreReason.SUBAGENT_DESCRIPTOR,
    "plan/mode" to DshIgnoreReason.POLICY_STATE,
    "agent/inbox/spliced" to DshIgnoreReason.INBOX,
    "step/end" to DshIgnoreReason.LIFECYCLE_NOISE,
    // log-only（设计 Tier3 明列）
    "request/header" to DshIgnoreReason.LOG_ONLY,
    "request/context" to DshIgnoreReason.LOG_ONLY,
    "session/end-seed" to DshIgnoreReason.LOG_ONLY,
    "web/deepseek-search-llm-request" to DshIgnoreReason.LOG_ONLY,
    "schedule/change" to DshIgnoreReason.LOG_ONLY,
    "feedback/record" to DshIgnoreReason.LOG_ONLY,
    // #310① A8轮3（2026-09-05）：服务器 known-event-types 词汇内的 log-only 事件——
    // 缺席曾使 session/page 整页返回后 fold 全量拒绝重建（子会话转录恒空）。
    "model/selection" to DshIgnoreReason.LOG_ONLY,
    "subagent/model-selection-policy" to DshIgnoreReason.LOG_ONLY,
    // durable 审批面：实况弹窗由 mux approval/requested|resolved 承载
    "approval/asked" to DshIgnoreReason.APPROVAL_DURABLE,
    "approval/decided" to DshIgnoreReason.APPROVAL_DURABLE,
    // 插件域（known-49 收尾；E2E 实证 llm/failover 曾致整会话拒绝重建）
    "llm/failover" to DshIgnoreReason.LLM_FAILOVER,
    "session/title-llm-request" to DshIgnoreReason.LOG_ONLY,
    "hook/invoked" to DshIgnoreReason.PLUGIN_DOMAIN,
    "hook/result" to DshIgnoreReason.PLUGIN_DOMAIN,
    "team/task" to DshIgnoreReason.PLUGIN_DOMAIN,
    "team/member" to DshIgnoreReason.PLUGIN_DOMAIN,
    "team/message/delivered" to DshIgnoreReason.PLUGIN_DOMAIN,
    "team/message/queued" to DshIgnoreReason.PLUGIN_DOMAIN,
    // tool-workflow 阶段明细（run-start/end 已映射为 synthetic 卡）
    "tool-workflow/agent-start" to DshIgnoreReason.WORKFLOW_AGENT,
    "tool-workflow/agent-end" to DshIgnoreReason.WORKFLOW_AGENT,
    // Mux 帧类型混入历史行（B.4 防御）
    "session/projection" to DshIgnoreReason.PROJECTION,
    "session/jobs" to DshIgnoreReason.JOBS,
    "session/queue" to DshIgnoreReason.QUEUE,
    "stream/error" to DshIgnoreReason.STREAM_ERROR,
)

/** V3（0.1.5-rc.1）新增已知忽略词汇。 */
private val V3_IGNORABLE: Map<String, String> = mapOf(
    // 瞬态 LLM 尝试记录（实测 692 例中 681 例随后 llm/retry，逐条渲染会刷屏）
    "assistant/attempt" to DshIgnoreReason.SESSION_FORMAT_V3,
    // 消息级反馈：本机 29 归档 0 样本，待新会话取证后补映射
    "feedback/message-put" to DshIgnoreReason.SESSION_FORMAT_V3,
    "feedback/message-delete" to DshIgnoreReason.SESSION_FORMAT_V3,
    // 子智能体目录单条推送：目录权威面是 subagents/list RPC 整帧（app 侧
    // SubagentModeTracker 已懒加载）；再落一处即双源，故维持具名降级
    "subagent/catalog" to DshIgnoreReason.SESSION_FORMAT_V3,
)
