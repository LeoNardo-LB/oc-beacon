package dev.leonardo.octether.domain.usecase

import app.cash.turbine.test
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.repository.SessionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetSessionListUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = GetSessionListUseCase(sessionRepository)

    @Test
    fun `invoke emits sessions for server`() = runTest {
        val sessions = listOf(Session(id = "s1", title = "Chat", time = Session.Time(created = 1000L, updated = 1000L)))
        every { sessionRepository.getSessionsFlow("srv1") } returns flowOf(sessions)

        useCase("srv1").test {
            assertEquals(sessions, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list`() = runTest {
        every { sessionRepository.getSessionsFlow("srv1") } returns flowOf(emptyList())

        useCase("srv1").test {
            assertEquals(emptyList<Session>(), awaitItem())
            awaitComplete()
        }
    }
}
