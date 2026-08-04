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
        // TODO：接入真实的 WebSocket PTY 流。
        // 接口使用 sessionId，但 PTY 方法使用 ptyId。
        // 该 flow 需要：createPty → openPtySocket(ptyId) → 发射帧。
        return flow { /* stub */ }
    }

    override suspend fun sendInput(serverId: String, sessionId: String, data: String): Result<Unit> = runCatching {
        // TODO：PTY 输入通过 PtySocket 的 WebSocket 帧发送，而非 REST 方法。
        // 需要持有 PtySocket 引用并调用 ptySocket.send(data)。
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        ServerConnection.from(config.url, config.username, config.password)
        // 在设计好 PtySocket 生命周期管理前为空操作
    }

    override suspend fun resize(serverId: String, sessionId: String, cols: Int, rows: Int): Result<Unit> = runCatching {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        val conn = ServerConnection.from(config.url, config.username, config.password)
        // TODO：接口使用 sessionId，但 updatePtySize 需要 ptyId。
        // 需要 sessionId→ptyId 映射，或将接口改为接受 ptyId。
        // terminalApi.updatePtySize(conn, sessionId, cols, rows)
    }
}
