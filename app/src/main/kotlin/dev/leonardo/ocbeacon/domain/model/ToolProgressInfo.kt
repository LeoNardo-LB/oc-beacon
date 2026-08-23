package dev.leonardo.ocbeacon.domain.model

/**
 * 当前工具执行进度的领域模型。
 * 对应 data.repository.handler.ToolProgressInfo。
 */
data class ToolProgressInfo(
    val callId: String,
    val partId: String,
    val tool: String,
    val status: String,
    val progress: String? = null,
    val title: String? = null,
    val output: String = "",
    /** #180（2026-08-21）：tool.progress metadata.sessionID——subagent Running 期子智能体会话推断源。 */
    val childSessionId: String? = null,
)
