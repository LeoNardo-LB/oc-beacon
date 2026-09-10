package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.workspace.WorkspaceApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session

/**
 * DSH 工作区域端口实现（#391 切片9）——薄委托到协议客户端。
 *
 * V011 线面缺该动词时由 [DshApiClient] 抛 UnsupportedServerCapability，端口层不重复判定。
 */
class DshWorkspacePort(
    private val dsh: DshApiClient,
) : WorkspaceApi {

    override suspend fun archiveSession(conn: ServerConnection, sessionId: String): List<String> =
        dsh.archiveSession(conn, sessionId)

    override suspend fun listSessionsIncludingBlank(conn: ServerConnection): List<Session> =
        dsh.listSessionsIncludingBlank(conn)
}
