package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.SseEvent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseClientReadTimeoutTest {

    // ============ #108 带超时行读取（半开 TCP 防护） ============

    @Test
    fun `readRawLineBytesWithTimeout returns null on silent channel instead of hanging`() = runTest {
        // 无数据且未关闭的 ByteChannel：readByte 永久挂起——半开 TCP
        // （kill -9/NAT 静默断）模拟。withTimeoutOrNull 在虚拟时间推进后
        // 取消挂起，返回 null 而非永久挂死。
        val channel = ByteChannel()

        val result = channel.readRawLineBytesWithTimeout(timeoutMs = 1_000)

        assertNull(result)
    }

    @Test
    fun `readRawLineBytesWithTimeout returns line when data available`() = runTest {
        val channel = ByteReadChannel("hello\n".encodeToByteArray())

        val result = channel.readRawLineBytesWithTimeout(timeoutMs = 1_000)

        assertTrue("hello".encodeToByteArray().contentEquals(result))
    }

    @Test
    fun `readRawLineBytesWithTimeout returns null when channel closed`() = runTest {
        val channel = ByteChannel()
        channel.close()

        val result = channel.readRawLineBytesWithTimeout(timeoutMs = 1_000)

        assertNull(result)
    }

    @Test
    fun `readRawLineBytesWithTimeout handles CRLF`() = runTest {
        val channel = ByteReadChannel("hello\r\n".encodeToByteArray())

        val result = channel.readRawLineBytesWithTimeout(timeoutMs = 1_000)

        assertTrue("hello".encodeToByteArray().contentEquals(result))
    }

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
    fun `tracker enterCooldown resets consecutive timeout count`() {
        // 2026-08-18 回归（SSE 冷却永续循环）：冷却到期后首个超时不得立即
        // 再进冷却——enterCooldown 必须清零连续计数（代价付清重新计数）。
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 3, cooldownDurationMs = 60_000L)
        repeat(3) { tracker.recordTimeout() }
        assertTrue(tracker.shouldEnterCooldown())
        tracker.enterCooldown()
        // 冷却后：一次超时不应再触发冷却（需要重新累积到阈值）
        tracker.recordTimeout()
        assertTrue(!tracker.shouldEnterCooldown())
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
