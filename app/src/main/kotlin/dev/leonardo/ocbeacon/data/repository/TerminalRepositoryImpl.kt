package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.TerminalEvent
import dev.leonardo.ocbeacon.domain.repository.TerminalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalRepositoryImpl @Inject constructor(
    private val serverRepo: ServerDataStore
) : TerminalRepository {

    override fun connectTerminal(serverId: String, sessionId: String): Flow<TerminalEvent> {
        // TODO: Wire to actual WebSocket PTY stream.
        // The interface uses sessionId but PTY methods use ptyId.
        // The flow requires: createPty → openPtySocket(ptyId) → emit frames.
        return flow { /* stub */ }
    }

    override suspend fun sendInput(serverId: String, sessionId: String, data: String): Result<Unit> = runCatching {
        // TODO: PTY input is sent via WebSocket frames through PtySocket, not a REST method.
        // Need to hold a PtySocket reference and call ptySocket.send(data).
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        ServerConnection.from(config.url, config.username, config.password)
        // No-op until PtySocket lifecycle management is designed
    }

    override suspend fun resize(serverId: String, sessionId: String, cols: Int, rows: Int): Result<Unit> = runCatching {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        val conn = ServerConnection.from(config.url, config.username, config.password)
        // TODO: Interface uses sessionId but updatePtySize requires ptyId.
        // Need a sessionId→ptyId mapping or change interface to accept ptyId.
        // terminalApi.updatePtySize(conn, sessionId, cols, rows)
    }
}
