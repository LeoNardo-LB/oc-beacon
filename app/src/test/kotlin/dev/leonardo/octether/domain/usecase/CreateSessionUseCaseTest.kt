package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.CreateSessionOpts
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = CreateSessionUseCase(sessionRepository)

    @Test
    fun `invoke returns session on success`() = runTest {
        val serverId = "server-1"
        val opts = CreateSessionOpts(title = "New Chat", directory = "/home/user/project")
        val expectedSession = Session(
            id = "s1",
            title = "New Chat",
            directory = "/home/user/project",
            time = Session.Time(created = 1000L, updated = 1000L)
        )
        coEvery { sessionRepository.createSession(serverId, opts) } returns Result.success(expectedSession)

        val result = useCase(serverId, opts)

        assertTrue(result.isSuccess)
        assertEquals("s1", result.getOrNull()?.id)
        assertEquals("New Chat", result.getOrNull()?.title)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val serverId = "server-1"
        val opts = CreateSessionOpts()
        coEvery { sessionRepository.createSession(serverId, opts) } returns Result.failure(
            RuntimeException("Server unreachable")
        )

        val result = useCase(serverId, opts)

        assertTrue(result.isFailure)
        assertEquals("Server unreachable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with parentId creates child session`() = runTest {
        val serverId = "server-1"
        val opts = CreateSessionOpts(parentId = "parent-1")
        val expectedSession = Session(
            id = "child-1",
            parentId = "parent-1",
            time = Session.Time(created = 2000L, updated = 2000L)
        )
        coEvery { sessionRepository.createSession(serverId, opts) } returns Result.success(expectedSession)

        val result = useCase(serverId, opts)

        assertTrue(result.isSuccess)
        assertEquals("parent-1", result.getOrNull()?.parentId)
    }
}
