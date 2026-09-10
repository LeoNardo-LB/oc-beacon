package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.subagent.SubagentApi
import dev.leonardo.ocbeacon.data.dto.request.PromptPart
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.SubagentCatalog

/**
 * DSH 子智能体域端口实现（#391 切片3）——薄委托到协议客户端。
 */
class DshSubagentPort(
    private val dsh: DshApiClient,
) : SubagentApi {

    override suspend fun subagentCatalog(conn: ServerConnection, parentSessionId: String): SubagentCatalog =
        dsh.subagentCatalog(conn, parentSessionId)

    override suspend fun subagentPrompt(
        conn: ServerConnection,
        parentSessionId: String,
        childSessionId: String,
        parts: List<PromptPart>,
        clientTimeZone: String?,
    ): String? = dsh.subagentPrompt(conn, parentSessionId, childSessionId, parts, clientTimeZone)

    override suspend fun subagentInterrupt(
        conn: ServerConnection,
        parentSessionId: String,
        childSessionId: String,
    ): Boolean = dsh.subagentInterrupt(conn, parentSessionId, childSessionId)
}
