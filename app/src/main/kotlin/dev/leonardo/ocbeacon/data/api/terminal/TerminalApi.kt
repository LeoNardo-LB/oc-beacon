package dev.leonardo.ocbeacon.data.api.terminal

import dev.leonardo.ocbeacon.data.dto.common.*
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection

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
