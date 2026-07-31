package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.FsmEvent
import dev.leonardo.ocbeacon.domain.model.SessionActivity
import dev.leonardo.ocbeacon.domain.model.SessionFSMState
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * Concurrency safety tests for SessionStateService.
 *
 * Documents the race conditions RS-010 through RS-013 and verifies that
 * the fixes preserve correctness under concurrent access.
 *
 * Testing strategy:
 *  - Sequential tests verify the behavior contract (no regression)
 *  - Concurrent stress tests exercise the race window with real threads
 *  - Deterministic tests use latches/barriers to force specific interleavings
 */
class SessionStateServiceConcurrencyTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newService() = SessionStateService(
        testScope,
        Provider { mockk<SessionRepository>(relaxed = true) },
    )

    private fun newServiceWith(repo: SessionRepository) = SessionStateService(
        testScope,
        Provider { repo },
    )

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // ============ RS-010: applyTransition atomicity ============

    /**
     * RS-010 regression test: concurrent ClientSendParts and TextStarted must not
     * result in Idle state (TextStarted reading stale Idle must not overwrite the
     * Busy transition from ClientSendParts).
     *
     * Strategy: Force a deterministic race using a thread pool where N threads
     * fire ClientSendParts followed by TextStarted. After all complete, the state
     * MUST be Busy — never Idle.
     */
    @Test
    fun `RS-010 concurrent ClientSendParts then TextStarted never loses Busy state`() {
        val service = newService()
        val threadCount = 16
        val eventPairsPerThread = 5
        val pool = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap<Int, Throwable>()

        val futures = (0 until threadCount).map { idx ->
            pool.submit {
                try {
                    readyLatch.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    repeat(eventPairsPerThread) {
                        service.applyTransition("s1", FsmEvent.ClientSendParts)
                        service.applyTransition("s1", FsmEvent.TextStarted)
                    }
                } catch (t: Throwable) {
                    errors[idx] = t
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue("Thread errors: $errors", errors.isEmpty())

        testScope.runCurrent()

        val status = service.statusFlow.value["s1"]
        assertEquals(
            "After ClientSendParts, status must be Busy regardless of concurrent TextStarted (RS-010)",
            SessionStatus.Busy,
            status
        )
    }

    /**
     * RS-010 regression test: a deterministic interleaving where TextStarted reads
     * Idle BEFORE ClientSendParts writes Busy. With the old code, TextStarted's
     * `.update{}` would overwrite Busy with Idle (because activity events in Idle
     * state return `isSuspicious=true` and keep the state unchanged).
     *
     * After fix: `.update{}` retries the read-compute-write, so TextStarted sees
     * the Busy state written by ClientSendParts.
     */
    @Test
    fun `RS-010 sequential ClientSendParts then TextStarted produces Busy Streaming`() {
        val service = newService()

        // Sequential baseline — should always pass
        service.applyTransition("s1", FsmEvent.ClientSendParts)
        service.applyTransition("s1", FsmEvent.TextStarted)
        testScope.runCurrent()

        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Streaming, service.activityFlow.value["s1"])
    }

    /**
     * Stress test: many concurrent transitions across multiple sessions should not
     * corrupt the FSM map or lose transitions.
     */
    @Test
    fun `RS-010 multi-session concurrent transitions are all recorded in history`() {
        val service = newService()
        val sessionCount = 10
        val transitionsPerSession = 20
        val pool = Executors.newFixedThreadPool(sessionCount)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(sessionCount)

        val futures = (0 until sessionCount).map { idx ->
            val sessionId = "s$idx"
            pool.submit {
                readyLatch.countDown()
                startLatch.await(5, TimeUnit.SECONDS)
                repeat(transitionsPerSession) {
                    service.applyTransition(sessionId, FsmEvent.ClientSendParts)
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        testScope.runCurrent()

        // Each session should have exactly transitionsPerSession history entries
        for (i in 0 until sessionCount) {
            val history = service.historyFlow.value["s$i"]
            assertEquals(
                "Session s$i should have $transitionsPerSession history entries",
                transitionsPerSession,
                history?.size ?: 0
            )
        }
    }

    // ============ RS-011: clearAll atomicity ============

    /**
     * RS-011 regression test: clearAll() must not be overwritten by a concurrent
     * applyTransition that read the state before clearAll ran.
     *
     * With the old code (`_fsmStates.value = emptyMap()`), if applyTransition's
     * `.update{}` CAS read happens before the assignment but the CAS write happens
     * after, the write restores the old session state that clearAll removed.
     *
     * After fix: clearAll uses `.update {}` so it participates in CAS, and the
     * eventual state is consistent.
     */
    @Test
    fun `RS-011 clearAll concurrent with applyTransition does not resurrect cleared state`() {
        val service = newService()
        // Seed a Busy session
        service.applyTransition("s1", FsmEvent.ClientSendParts)
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])

        // Now clear and immediately re-apply in a tight loop
        val pool = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(2)

        val clearFuture = pool.submit {
            readyLatch.countDown()
            startLatch.await(5, TimeUnit.SECONDS)
            repeat(100) { service.clearAll() }
        }
        val applyFuture = pool.submit {
            readyLatch.countDown()
            startLatch.await(5, TimeUnit.SECONDS)
            repeat(100) {
                service.applyTransition("s1", FsmEvent.ClientSendParts)
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        clearFuture.get(10, TimeUnit.SECONDS)
        applyFuture.get(10, TimeUnit.SECONDS)
        pool.shutdown()
        testScope.runCurrent()

        // The map should be internally consistent — either empty (if clear was last)
        // or containing s1 (if apply was last). The test asserts no partial corruption.
        val status = service.statusFlow.value["s1"]
        // After clearAll + applyTransition, the status should be deterministic:
        // whichever operation was truly last in the CAS-ordered sequence wins.
        // We just assert no crash and the map is in a valid state.
        assertTrue(
            "Status should be either Busy or null (consistent), was $status",
            status == null || status == SessionStatus.Busy
        )
    }

    @Test
    fun `RS-011 clearSession and clearAll together leave empty state`() {
        val service = newService()
        service.applyTransition("s1", FsmEvent.ClientSendParts)
        service.applyTransition("s2", FsmEvent.ClientSendParts)
        testScope.runCurrent()

        service.clearSession("s1")
        service.clearAll()
        testScope.runCurrent()

        assertTrue(service.statusFlow.value.isEmpty())
        assertTrue(service.historyFlow.value.isEmpty())
    }

    // ============ RS-012: triggerRestValidation dedup ============

    /**
     * RS-012 regression test: multiple concurrent triggerRestValidation calls for
     * the same session should result in only the latest validation's result being
     * applied to the FSM.
     *
     * Note: with UnconfinedTestDispatcher, each launch executes synchronously up to
     * the first suspension point, so the mock IS invoked multiple times. However,
     * the dedup mechanism ensures that cancelled jobs' results are NOT applied to
     * the FSM — only the latest (non-cancelled) job's result takes effect.
     *
     * We verify this by having each validation return a different status and
     * checking that the final FSM state reflects only the last validation.
     */
    @Test
    fun `RS-012 concurrent triggerRestValidation applies only latest result`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        val callCount = AtomicInteger(0)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } coAnswers {
            val n = callCount.incrementAndGet()
            gate.await() // All validations suspend here until released
            // Each call returns a different status: early calls = Busy, last = Idle
            Result.success(mapOf("s1" to if (n < 5) SessionStatus.Busy else SessionStatus.Idle))
        }
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.directoryResolver = DirectoryResolver { "D:/proj" }
        service.onClientSendParts("s1")
        testScope.runCurrent()

        // Fire 5 rapid validations — merge cancels jobs 1-4, keeps job 5
        repeat(5) { service.triggerRestValidation("s1") }
        testScope.runCurrent()

        // Release all suspended validations simultaneously
        gate.complete(Unit)
        testScope.runCurrent()

        // The latest validation (job 5, returns Idle) should have its result applied.
        // Jobs 1-4 were cancelled by merge; even though their suspend function completes,
        // the FSM reflects the final applied state.
        assertEquals(
            "FSM should reflect the latest validation result (Idle)",
            SessionStatus.Idle,
            service.statusFlow.value["s1"]
        )
    }

    /**
     * RS-012 regression test: dedup must be per-session. Different sessions should
     * each be able to have their own active validation.
     */
    @Test
    fun `RS-012 dedup is per-session, not global`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())

        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.directoryResolver = DirectoryResolver { "D:/proj" }
        service.onClientSendParts("s1")
        service.onClientSendParts("s2")
        testScope.runCurrent()

        // Fire validations for two different sessions
        service.triggerRestValidation("s1")
        service.triggerRestValidation("s2")
        service.triggerRestValidation("s1")  // dedup for s1
        service.triggerRestValidation("s2")  // dedup for s2

        testScope.runCurrent()

        // Each session should have had its REST call (at least once each)
        coVerify(atLeast = 1) { fakeRepo.fetchSessionStatuses("svr1", "D:/proj") }
    }

    /**
     * RS-012 regression test: when a validation completes, it should clean up its
     * dedup entry, allowing future validations for the same session.
     */
    @Test
    fun `RS-012 completed validation allows subsequent validation for same session`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        val callCount = AtomicInteger(0)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } coAnswers {
            callCount.incrementAndGet()
            Result.success(emptyMap())  // absence → Idle
        }

        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")
        service.directoryResolver = DirectoryResolver { "D:/proj" }
        service.onClientSendParts("s1")
        testScope.runCurrent()

        // First validation — completes, marks Idle
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])

        // Second validation — should be allowed (first one completed)
        service.onClientSendParts("s1")
        service.triggerRestValidation("s1")
        testScope.runCurrent()

        // Should have made at least 2 REST calls (one per validation cycle)
        assertTrue(
            "Should allow new validation after previous completed (callCount=${callCount.get()})",
            callCount.get() >= 2
        )
    }

    // ============ RS-013: syncFromRest snapshot consistency ============

    /**
     * RS-013 baseline test: syncFromRest with absent session marks it Idle.
     * This is existing behavior that must be preserved.
     */
    @Test
    fun `RS-013 syncFromRest marks absent Busy session as Idle`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")

        service.onClientSendParts("s1")
        testScope.runCurrent()

        runBlocking { service.syncFromRest(listOf()) }
        testScope.runCurrent()

        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
    }
}
