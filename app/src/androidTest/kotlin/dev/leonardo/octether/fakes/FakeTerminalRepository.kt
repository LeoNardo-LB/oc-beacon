package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.TerminalEvent
import dev.leonardo.octether.domain.repository.TerminalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Singleton

@Singleton
class FakeTerminalRepository @Inject constructor() : TerminalRepository {

    var sendInputResult: Result<Unit> = Result.success(Unit)
    var resizeResult: Result<Unit> = Result.success(Unit)

    override fun connectTerminal(serverId: String, sessionId: String): Flow<TerminalEvent> = flowOf()

    override suspend fun sendInput(serverId: String, sessionId: String, data: String): Result<Unit> = sendInputResult

    override suspend fun resize(serverId: String, sessionId: String, cols: Int, rows: Int): Result<Unit> = resizeResult
}
