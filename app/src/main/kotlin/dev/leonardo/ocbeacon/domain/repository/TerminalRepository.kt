package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.TerminalEvent
import kotlinx.coroutines.flow.Flow

interface TerminalRepository {
    fun connectTerminal(serverId: String, sessionId: String): Flow<TerminalEvent>
    suspend fun sendInput(serverId: String, sessionId: String, data: String): Result<Unit>
    suspend fun resize(serverId: String, sessionId: String, cols: Int, rows: Int): Result<Unit>
}
