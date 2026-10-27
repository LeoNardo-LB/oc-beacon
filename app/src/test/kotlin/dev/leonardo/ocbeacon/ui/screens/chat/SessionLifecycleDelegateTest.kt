package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.ui.navigation.routes.ChatNav
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleDelegateTest {

    private fun mkSession(id: String, dir: String = "/proj") = Session(
        id = id,
        directory = dir,
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun newSessionHandle(sessionId: String = "", directory: String = "") =
        SavedStateHandle(mapOf(
            "sessionId" to sessionId,
            ChatNav.PARAM_DIRECTORY to directory
        ))

    @Test
    fun `ensureSession returns existing id without creating`() = runTest {
        val useCase = mockk<ManageSessionUseCase>(relaxed = true)
        val repo = mockk<SessionRepository>(relaxed = true)
        val delegate = SessionLifecycleDelegate(
            useCase, repo, "srv",
            newSessionHandle(sessionId = "existing"),
            this, {}, {}
        )

        val result = delegate.ensureSession()

        assertEquals("existing", result)
        coVerify(exactly = 0) { useCase.createSession(any(), any()) }
    }

    @Test
    fun `ensureSession creates session when empty`() = runTest {
        val useCase = mockk<ManageSessionUseCase>()
        val repo = mockk<SessionRepository>(relaxed = true)
        val session = mkSession("new-1", "/proj")
        coEvery { useCase.createSession("srv", any()) } returns session

        val delegate = SessionLifecycleDelegate(
            useCase, repo, "srv",
            newSessionHandle(),
            this, {}, {}
        )

        val result = delegate.ensureSession()

        assertEquals("new-1", result)
        assertEquals("/proj", delegate.sessionDirectory)
        coVerify(exactly = 1) { useCase.createSession("srv", any()) }
        coVerify { repo.setSessions("srv", listOf(session)) }
    }

    @Test
    fun `ensureSession materialization invokes onSessionMaterialized with real id - 568`() = runTest {
        val useCase = mockk<ManageSessionUseCase>()
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { useCase.createSession("srv", any()) } returns mkSession("mat-1", "/proj")
        var materializedId: String? = null
        var callCount = 0

        val delegate = SessionLifecycleDelegate(
            useCase, repo, "srv",
            newSessionHandle(),
            this, {}, {},
            onSessionMaterialized = { id -> materializedId = id; callCount++ }
        )

        delegate.ensureSession()

        assertEquals("mat-1", materializedId)
        // 重复 ensureSession（幂等早退路径）不再触发迁移回调
        delegate.ensureSession()
        assertEquals(1, callCount)
    }

    @Test
    fun `ensureSession existing session does not invoke materialization callback - 568`() = runTest {
        val useCase = mockk<ManageSessionUseCase>(relaxed = true)
        val repo = mockk<SessionRepository>(relaxed = true)
        var called = false

        val delegate = SessionLifecycleDelegate(
            useCase, repo, "srv",
            newSessionHandle(sessionId = "existing"),
            this, {}, {},
            onSessionMaterialized = { called = true }
        )

        delegate.ensureSession()

        assertFalse(called)
    }

    @Test
    fun `ensureSession concurrent calls create only once`() = runTest {
        val useCase = mockk<ManageSessionUseCase>()
        val repo = mockk<SessionRepository>(relaxed = true)
        val session = mkSession("concurrent-1")

        val gate = CompletableDeferred<Session>()
        coEvery { useCase.createSession(any(), any()) } coAnswers { gate.await() }

        val delegate = SessionLifecycleDelegate(
            useCase, repo, "srv",
            newSessionHandle(),
            this, {}, {}
        )

        val results = mutableListOf<String>()
        val job1 = launch { results.add(delegate.ensureSession()) }
        val job2 = launch { results.add(delegate.ensureSession()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { useCase.createSession(any(), any()) }

        gate.complete(session)
        advanceUntilIdle()

        job1.join(); job2.join()
        coVerify(exactly = 1) { useCase.createSession(any(), any()) }
        assertTrue(results.all { it == "concurrent-1" })
    }

    @Test
    fun `initForNewSession sets directory from param`() = runTest {
        val delegate = SessionLifecycleDelegate(
            mockk(relaxed = true), mockk(relaxed = true), "srv",
            newSessionHandle(directory = "/my/project"),
            this, {}, {}
        )

        delegate.initForNewSession()

        assertEquals("/my/project", delegate.sessionDirectory)
        assertTrue(delegate.sessionLoaded.isCompleted)
    }
}
