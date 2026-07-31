package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = ManageSessionUseCase(sessionRepository)

    @Test
    fun `getSession delegates to sessionRepository`() = runTest {
        val session = Session(id = "s1", title = "Chat", time = Session.Time(created = 1000L, updated = 1000L))
        coEvery { sessionRepository.getSession("server1", "s1") } returns Result.success(session)

        val result = useCase.getSession("server1", "s1")

        assertEquals(session, result)
    }

    @Test
    fun `listMessages delegates to sessionRepository`() = runTest {
        coEvery { sessionRepository.listMessages("server1", "s1", any()) } returns Result.success(emptyList())

        val result = useCase.listMessages("server1", "s1", limit = 50)

        assertTrue(result.isEmpty())
    }
}
