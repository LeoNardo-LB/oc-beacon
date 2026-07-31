package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = DeleteSessionUseCase(sessionRepository)

    @Test
    fun `invoke returns success when repository succeeds`() = runTest {
        coEvery { sessionRepository.deleteSession("server1", "s1") } returns Result.success(Unit)

        val result = useCase("server1", "s1")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { sessionRepository.deleteSession("server1", "nonexistent") } returns Result.failure(
            NoSuchElementException("Session not found")
        )

        val result = useCase("server1", "nonexistent")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `invoke returns failure on network error`() = runTest {
        coEvery { sessionRepository.deleteSession("server1", "s1") } returns Result.failure(
            java.io.IOException("Connection reset")
        )

        val result = useCase("server1", "s1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }
}
