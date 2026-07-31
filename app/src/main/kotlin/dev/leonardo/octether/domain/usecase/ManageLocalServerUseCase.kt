package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.LocalServerState
import dev.leonardo.octether.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage local server (start/stop/status/setup).
 */
class ManageLocalServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    fun getSetupCommand(): String = serverRepository.getLocalSetupCommand()

    suspend fun setup(): Result<Unit> = serverRepository.setupLocalServer()

    suspend fun start(): Result<Unit> = serverRepository.startLocalServer()

    suspend fun stop(): Result<Unit> = serverRepository.stopLocalServer()

    suspend fun getState(): Result<LocalServerState> = serverRepository.getLocalServerState()
}
