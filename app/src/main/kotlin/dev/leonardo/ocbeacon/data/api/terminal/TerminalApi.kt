package dev.leonardo.ocbeacon.data.api.terminal

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

@Singleton
class TerminalApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : TerminalApi {

    override suspend fun createPty(
        conn: ServerConnection,
        title: String?,
        cwd: String?,
        directory: String?
    ): PtyInfo =
        if (conn.apiVersion.isV2) v2.createPty(conn, title, cwd, directory)
        else v1.createPty(conn, title, cwd, directory)

    override suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean =
        if (conn.apiVersion.isV2) v2.removePty(conn, ptyId) else v1.removePty(conn, ptyId)

    override suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String?
    ): Boolean =
        if (conn.apiVersion.isV2) v2.updatePtySize(conn, ptyId, cols, rows, directory)
        else v1.updatePtySize(conn, ptyId, cols, rows, directory)

    override suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int,
        directory: String?
    ): PtySocket =
        if (conn.apiVersion.isV2) v2.openPtySocket(conn, ptyId, cursor, directory)
        else v1.openPtySocket(conn, ptyId, cursor, directory)

    override suspend fun listPtyShells(conn: ServerConnection, directory: String?): List<ShellInfo> =
        if (conn.apiVersion.isV2) v2.listPtyShells(conn, directory) else v1.listPtyShells(conn, directory)

    override suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection?,
        directory: String?
    ): Boolean =
        if (conn.apiVersion.isV2) v2.runShellCommand(conn, sessionId, command, agent, model, directory)
        else v1.runShellCommand(conn, sessionId, command, agent, model, directory)
}
