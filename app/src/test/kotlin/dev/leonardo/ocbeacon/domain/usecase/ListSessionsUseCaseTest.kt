package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSessionsUseCaseTest {

    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val sut = ListSessionsUseCase(sessionRepository)

    private val serverId = "srv1"

    @Test
    fun `passes all pagination parameters to repository`() = runTest {
        val expected = listOf(Session(id = "s1", time = Session.Time(0, 0)))
        coEvery {
            sessionRepository.listSessions(any(), any(), any(), any(), any())
        } returns expected

        val result = sut(serverId, directory = "/proj", search = "q", cursor = "abc", limit = 25)

        assertEquals(expected, result)
        coVerify {
            sessionRepository.listSessions(serverId, "/proj", "q", "abc", 25)
        }
    }

    @Test
    fun `uses default values when optional params omitted`() = runTest {
        coEvery { sessionRepository.listSessions(any(), any(), any(), any(), any()) } returns emptyList()

        sut(serverId)

        coVerify {
            sessionRepository.listSessions(serverId, null, null, null, 50)
        }
    }
}
