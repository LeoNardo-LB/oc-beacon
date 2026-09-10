package dev.leonardo.ocbeacon.data.api.workspace

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session

/**
 * 工作区连接语义端口（#391 切片9）。
 *
 * 承载 workspace 域的两项写/读语义：归档写操作（回执 = 新归档集合）与含 blank
 * 空壳的全量会话列表（连接复用判定候选源）。端口缺席 = 该类型无工作区连接语义，
 * 调用方按空快照形态降级，不按服务器类型判断。
 */
interface WorkspaceApi {
    /** 归档会话；回执即新的完整归档集合（幂等 add-only，见 SessionTreeList）。 */
    suspend fun archiveSession(conn: ServerConnection, sessionId: String): List<String>

    /** session.list 全量（含 blank 空壳）——列表流另行滤除 blank。 */
    suspend fun listSessionsIncludingBlank(conn: ServerConnection): List<Session>
}
