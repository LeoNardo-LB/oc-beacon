package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.ProviderInfo
import dev.leonardo.octether.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageServerProvidersUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = ManageServerProvidersUseCase(serverRepository)

    @Test
    fun `loadProviders returns provider list`() = runTest {
        val providers = listOf(ProviderInfo(id = "openrouter", name = "OpenRouter", enabled = true))
        coEvery { serverRepository.loadProviders("srv1") } returns Result.success(providers)

        val result = useCase.loadProviders("srv1")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
    }

    @Test
    fun `loadProviders returns failure on error`() = runTest {
        coEvery { serverRepository.loadProviders("srv1") } returns Result.failure(
            RuntimeException("Server not connected")
        )

        val result = useCase.loadProviders("srv1")

        assertTrue(result.isFailure)
    }
}
