package dev.leonardo.ocbeacon.data.api.message

/**
 * V2 prompt 受理回执（POST /api/session/{id}/prompt 响应体 data 对象）。
 *
 * 2026-08-14 根治方案（用户消息"发送后无气泡"系统性修复）：
 * - V2 prompt 的 200 响应体即 Inbox 条目（id/sessionID/payload.text），
 *   与 SSE session.inbox.enqueued 的 data 同构——发送后**立即本地播种**
 *   用户消息（不等 SSE 回显），SSE 到达时同 id 幂等合并。
 * - 效果：发送即显示（~100ms）；SSE 丢失/延迟/服务器版本差异不再导致
 *   用户消息气泡缺失（悲观消息设计的兜底）。
 * - V1 prompt_async 为 204 无响应体 → 返回 null（依赖 V1 SSE 回显）。
 */
data class PromptAdmission(
    /** 消息 id（msg_xxx）——与 SSE inbox.enqueued 的 inboxID 同源。 */
    val id: String,
    val sessionId: String,
    /** 用户消息文本（prompt payload.text）。 */
    val text: String?,
)
