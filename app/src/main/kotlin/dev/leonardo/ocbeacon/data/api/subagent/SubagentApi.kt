package dev.leonardo.ocbeacon.data.api.subagent

import dev.leonardo.ocbeacon.data.dto.request.PromptPart
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.SubagentCatalog

/**
 * 子智能体域端口（#391 切片3）。
 *
 * 端口在场即该能力可用；不提供的类型端口缺席——目录读返回空（调用方走本地会话镜像），
 * 续聊写显式失败（静默假成功会误导用户）。
 */
interface SubagentApi {

    /** subagents/list 整帧域投影（entries + parentAvailable）。 */
    suspend fun subagentCatalog(conn: ServerConnection, parentSessionId: String): SubagentCatalog

    /** subagents/prompt（mode=continuable）续聊；回执 messageId。 */
    suspend fun subagentPrompt(
        conn: ServerConnection,
        parentSessionId: String,
        childSessionId: String,
        parts: List<PromptPart>,
        clientTimeZone: String? = null,
    ): String?

    /** subagents/interruptByParent：durable 父址中断。 */
    suspend fun subagentInterrupt(
        conn: ServerConnection,
        parentSessionId: String,
        childSessionId: String,
    ): Boolean
}
