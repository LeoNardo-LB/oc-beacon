package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectServerUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = DisconnectServerUseCase(serverRepository)

    @Test
    fun `invoke returns success on disconnect`() = runTest {
        coEvery { serverRepository.disconnect("srv1") } returns Result.success(Unit)

        val result = useCase("srv1")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure on disconnect error`() = runTest {
        coEvery { serverRepository.disconnect("srv1") } returns Result.failure(
            RuntimeException("Not connected")
        )

        val result = useCase("srv1")

        assertTrue(result.isFailure)
    }
}
