package dev.leonardo.ocbeacon.data.api.terminal

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import javax.inject.Inject
import javax.inject.Singleton

interface TerminalApi {
    suspend fun createPty(
        conn: ServerConnection,
        title: String? = null,
        cwd: String? = null,
        directory: String? = null
    ): PtyInfo

    suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean

    suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String? = null
    ): Boolean

    suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int = -1,
        directory: String? = null
    ): PtySocket

    suspend fun listPtyShells(conn: ServerConnection, directory: String? = null): List<ShellInfo>

    /**
     * 在会话中运行 shell 命令。
     * POST /session/{sessionId}/shell
     */
    suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection? = null,
        directory: String? = null
    ): Boolean
}

/**
 * C1-5（2026-08-27，#238 五域收编）：分发层收缩为单点路由 + 逐方法单行委托。
 * [V1ApiClient]/[V2ApiClient] 已直接实现 [TerminalApi]。
 */
@Singleton
class TerminalApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient,
    private val dsh: DshApiClient,
) : TerminalApi {

    /** #276 三分：serverType==Dsh 优先（apiVersion 不参与 DSH 路由，设计 §2.1）。 */
    private fun pick(conn: ServerConnection): TerminalApi = when (conn.serverType) {
        dev.leonardo.ocbeacon.domain.model.ServerType.Dsh -> dsh
        else -> if (conn.apiVersion.isV2) v2 else v1
    }

    override suspend fun createPty(
        conn: ServerConnection,
        title: String?,
        cwd: String?,
        directory: String?
    ): PtyInfo = pick(conn).createPty(conn, title, cwd, directory)

    override suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean =
        pick(conn).removePty(conn, ptyId)

    override suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String?
    ): Boolean = pick(conn).updatePtySize(conn, ptyId, cols, rows, directory)

    override suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int,
        directory: String?
    ): PtySocket = pick(conn).openPtySocket(conn, ptyId, cursor, directory)

    override suspend fun listPtyShells(conn: ServerConnection, directory: String?): List<ShellInfo> =
        pick(conn).listPtyShells(conn, directory)

    override suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection?,
        directory: String?
    ): Boolean = pick(conn).runShellCommand(conn, sessionId, command, agent, model, directory)
}
