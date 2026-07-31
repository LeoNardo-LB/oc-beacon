package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectServerUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = ConnectServerUseCase(serverRepository)

    @Test
    fun `invoke returns success on connect`() = runTest {
        val config = ServerConfig(id = "srv1", url = "http://host:4096")
        coEvery { serverRepository.connect(config) } returns Result.success(Unit)

        val result = useCase(config)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure on connect error`() = runTest {
        val config = ServerConfig(id = "srv1", url = "http://host:4096")
        coEvery { serverRepository.connect(config) } returns Result.failure(
            java.io.IOException("Connection refused")
        )

        val result = useCase(config)

        assertTrue(result.isFailure)
    }
}
