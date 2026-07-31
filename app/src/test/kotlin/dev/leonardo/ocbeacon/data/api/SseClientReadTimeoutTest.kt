package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.SseEvent
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseClientReadTimeoutTest {

    // ============ SseReadTimeoutTracker ============

    @Test
    fun `tracker starts with zero consecutive timeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        assertEquals(0, tracker.consecutiveTimeouts)
        assertTrue(!tracker.shouldEnterCooldown())
    }

    @Test
    fun `tracker increments on recordTimeout`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        assertEquals(1, tracker.consecutiveTimeouts)
    }

    @Test
    fun `tracker resets on recordSuccess`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        tracker.recordTimeout()
        tracker.recordSuccess()
        assertEquals(0, tracker.consecutiveTimeouts)
    }

    @Test
    fun `tracker shouldEnterCooldown after maxConsecutiveTimeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 3, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        assertTrue(!tracker.shouldEnterCooldown())
        tracker.recordTimeout()
        assertTrue(!tracker.shouldEnterCooldown())
        tracker.recordTimeout()
        assertTrue(tracker.shouldEnterCooldown())
    }

    @Test
    fun `tracker isInCooldown returns false initially`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        assertTrue(!tracker.isInCooldown())
    }

    @Test
    fun `tracker enterCooldown sets isInCooldown true`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())
    }

    @Test
    fun `tracker reset clears cooldown and timeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        tracker.recordTimeout()
        tracker.recordTimeout()
        tracker.recordTimeout()
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())

        tracker.reset()
        assertEquals(0, tracker.consecutiveTimeouts)
        assertTrue(!tracker.isInCooldown())
    }

    @Test
    fun `default constants are correct`() {
        assertEquals(30_000L, SseClientDefaults.DEFAULT_READ_TIMEOUT_MS)
        assertEquals(5, SseClientDefaults.MAX_CONSECUTIVE_TIMEOUTS)
        assertEquals(300_000L, SseClientDefaults.COOLDOWN_DURATION_MS)
    }
}
