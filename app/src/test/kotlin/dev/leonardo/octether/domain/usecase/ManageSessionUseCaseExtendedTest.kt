package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ManageSessionUseCaseExtendedTest {

    private val sessionRepository: SessionRepository = mockk()
    private lateinit var useCase: ManageSessionUseCase
    private val baseSession = Session(
        id = "s1",
        title = "Test",
        time = Session.Time(created = 1000L, updated = 1000L)
    )

    @Before
    fun setup() {
        useCase = ManageSessionUseCase(sessionRepository)
    }

    @Test
    fun `deleteMessage delegates to sessionRepository`() = runTest {
        coEvery { sessionRepository.deleteMessage("server1", "s1", "m1") } returns Result.success(true)

        val result = useCase.deleteMessage("server1", "s1", "m1")

        assertTrue(result)
    }

    @Test
    fun `deleteMessage returns false on failure`() = runTest {
        coEvery { sessionRepository.deleteMessage("server1", "s1", "m1") } returns Result.success(false)

        val result = useCase.deleteMessage("server1", "s1", "m1")

        assertFalse(result)
    }

    @Test
    fun `deleteMessagePart delegates to sessionRepository`() = runTest {
        coEvery { sessionRepository.deleteMessagePart("server1", "s1", "m1", 0) } returns Result.success(true)

        val result = useCase.deleteMessagePart("server1", "s1", "m1", 0)

        assertTrue(result)
    }

    @Test
    fun `deleteMessagePart returns false on failure`() = runTest {
        coEvery { sessionRepository.deleteMessagePart("server1", "s1", "m1", 2) } returns Result.success(false)

        val result = useCase.deleteMessagePart("server1", "s1", "m1", 2)

        assertFalse(result)
    }

    @Test
    fun `archiveSession delegates to sessionRepository`() = runTest {
        val archived = baseSession.copy(
            time = baseSession.time.copy(archived = 2000L)
        )
        coEvery { sessionRepository.archive("server1", "s1") } returns Result.success(archived)

        val result = useCase.archiveSession("server1", "s1")

        assertEquals(2000L, result.time.archived)
    }

    @Test
    fun `unarchiveSession delegates to sessionRepository`() = runTest {
        coEvery { sessionRepository.unarchive("server1", "s1") } returns Result.success(baseSession)

        val result = useCase.unarchiveSession("server1", "s1")

        assertEquals(null, result.time.archived)
    }

    @Test
    fun `importSession delegates to sessionRepository`() = runTest {
        val imported = Session(
            id = "imported-1",
            title = "Imported",
            time = Session.Time(created = 3000L, updated = 3000L)
        )
        coEvery { sessionRepository.importSession("server1", "https://share.example.com/s/abc") } returns Result.success(imported)

        val result = useCase.importSession("server1", "https://share.example.com/s/abc")

        assertEquals("imported-1", result.id)
        assertEquals("Imported", result.title)
    }
}
