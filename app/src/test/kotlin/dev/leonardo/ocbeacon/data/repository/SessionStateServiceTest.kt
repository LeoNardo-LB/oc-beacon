package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

/**
 * Fixture note (deviation from brief's literal `runTest { this -> ... }` form):
 *
 * SessionStateService exposes flows built with `stateIn(appScope, SharingStarted.Eagerly, …)`,
 * which launch coroutines in the injected appScope that never complete on their own. Passing
 * `runTest`'s `this` (a TestScope on the default StandardTestDispatcher) caused two failures:
 *   1. Timing — the Eagerly collector was queued behind the test body, so `statusFlow.value`
 *      stayed `emptyMap()` (AssertionError / NPE).
 *   2. `UncompletedCoroutinesError` at teardown — the 3 Eagerly coroutines outlive the test body.
 *
 * Fix mirrors the project's own `ChatViewModelStreamingTest` (UnconfinedTestDispatcher +
 * advanceUntilIdle): drive appScope with an UnconfinedTestDispatcher for eager propagation, and
 * cancel the scope in @After so teardown sees no uncompleted coroutines. All test cases and
 * assertions are unchanged from the brief.
 *
 * ---
 * Task 4 fixture amendment (staleness guard):
 *
 * Task 4 starts a perpetual `while(isActive) { delay(STALENESS_CHECK_INTERVAL_MS); ... }`
 * coroutine in `init`. Probe (see git history) confirmed that `advanceUntilIdle()` loops forever
 * on such a coroutine (10s JUnit timeout, advanced virtual time to ~423 days). `runCurrent()`
 * runs only tasks scheduled at the current virtual time and does NOT advance the clock, so the
 * guard's first delay(5_000) is never reached. All assertions hold under `runCurrent()` because:
 *   - `applyTransition` is synchronous and writes `_fsmStates` immediately.
 *   - `statusFlow` uses `stateIn(appScope, SharingStarted.Eagerly, …)`; under UnconfinedTestDispatcher
 *     the operator chain propagates synchronously, and `runCurrent()` flushes any queued dispatch.
 *   - `triggerRestValidation` launches a coroutine whose body has no real suspension point under
 *     relaxed MockK (`coEvery`'s stub returns immediately), so it completes during `runCurrent()`.
 *
 * Therefore ALL tests use `runCurrent()` instead of `advanceUntilIdle()`. The `@After cancel`
 * still cancels the staleness guard's `Job` along with every other child of `testScope`.
 */
class SessionStateServiceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newService() = SessionStateService(
        testScope,
        Provider { mockk<SessionRepository>(relaxed = true) },
    )

    /** Build a service backed by [repo] so tests can stub `fetchSessionStatuses`. */
    private fun newServiceWith(repo: SessionRepository) = SessionStateService(
        testScope,
        Provider { repo },
    )

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `ClientSendParts transitions Idle to Busy Waiting in statusFlow`() {
        val service = newService()
        service.onClientSendParts("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Waiting, service.activityFlow.value["s1"])
    }

    @Test
    fun `SseIdle after Busy triggers forceComplete on messageForceCompleter`() {
        val forceCompleter = mockk<MessageForceCompleter>(relaxed = true)
        val service = newService()
        service.messageForceCompleter = forceCompleter
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
        verify { forceCompleter.markIdle("s1") }
    }

    @Test
    fun `transition recorded in history`() {
        val service = newService()
        service.onClientSendParts("s1")
        testScope.runCurrent()
        val history = service.historyFlow.value["s1"]
        assertEquals(1, history!!.size)
        assertEquals("Idle", history[0].fromCore)
        assertEquals("Busy", history[0].toCore)
    }

    @Test
    fun `history trims to max 20 entries`() {
        val service = newService()
        repeat(25) { service.onClientSendParts("s1") }  // each is a transition
        testScope.runCurrent()
        val history = service.historyFlow.value["s1"]!!
        assertTrue("history should be trimmed to <= 20, was ${history.size}", history.size <= 20)
    }

    @Test
    fun `clearSession removes state and history`() {
        val service = newService()
        service.onClientSendParts("s1")
        testScope.runCurrent()
        service.clearSession("s1")
        testScope.runCurrent()
        assertNull(service.statusFlow.value["s1"])
        assertNull(service.historyFlow.value["s1"])
    }

    // ============ Task 4: triggerRestValidation absence=idle ============

    @Test
    fun `triggerRestValidation absence with known directory marks Idle`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())  // absent
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.directoryResolver = DirectoryResolver { "D:/proj" }
        service.onClientSendParts("s1")
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
    }

    @Test
    fun `triggerRestValidation absence with null directory stays Busy`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.directoryResolver = DirectoryResolver { null }  // unknown dir
        service.onClientSendParts("s1")
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // no false idle
    }

    // ============ Task 5: syncFromRest ============

    @Test
    fun `syncFromRest aggregates multiple directories`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses("svr1", "D:/projA") } returns Result.success(mapOf("s1" to SessionStatus.Busy))
        coEvery { fakeRepo.fetchSessionStatuses("svr1", "D:/projB") } returns Result.success(mapOf("s2" to SessionStatus.Busy))
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        val result = runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/projA"), Project(worktree = "D:/projB"))) }
        testScope.runCurrent()
        assertEquals(2, result.totalSessions)
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s2"])
    }

    @Test
    fun `syncFromRest marks absent non-idle session Idle when no incomplete`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.onClientSendParts("s1")  // local Busy
        runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/p"))) }
        testScope.runCurrent()
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
    }

    @Test
    fun `syncFromRest protects absent session with incomplete messages`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.directoryResolver = DirectoryResolver { "D:/p" }
        service.incompleteChecker = IncompleteAssistantChecker { true }
        service.onClientSendParts("s1")
        runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/p"))) }
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // protected
    }
}
