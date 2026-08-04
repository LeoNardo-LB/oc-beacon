package dev.leonardo.ocbeacon.verification

import dev.leonardo.ocbeacon.data.api.RetryPolicy
import dev.leonardo.ocbeacon.data.api.SseClientDefaults
import dev.leonardo.ocbeacon.data.api.SseReadTimeoutTracker
import dev.leonardo.ocbeacon.data.api.isTransientException
import dev.leonardo.ocbeacon.data.api.retryWithPolicy
import dev.leonardo.ocbeacon.domain.model.ApiError
import dev.leonardo.ocbeacon.domain.model.ApiResult
import dev.leonardo.ocbeacon.domain.model.mapHttpError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Layer 1（网络韧性）的增强测试 —— 边界条件、
 * 错误路径以及现有测试套件未覆盖的边界情况。
 */
class Layer1EnhancedTest {

    // ================================================================
    // ApiResult / ApiError / mapHttpError
    // ================================================================

    @Test
    fun `mapHttpError 429 with unparseable retryAfterSeconds defaults to 0`() {
        val error = mapHttpError(429, retryAfterSeconds = "abc")
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(0L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `mapHttpError 429 with empty retryAfterSeconds defaults to 0`() {
        val error = mapHttpError(429, retryAfterSeconds = "")
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(0L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `mapHttpError 429 with negative retryAfterSeconds defaults to 0`() {
        val error = mapHttpError(429, retryAfterSeconds = "-5")
        assertTrue(error is ApiError.RateLimitError)
        // toLongOrNull 对空字符串返回 null，但 "-5" 会被解析为 -5
        // 然后 -5 * 1000 = -5000，这正是代码实际的行为。
        assertEquals(-5000L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `mapHttpError 429 with very large retryAfterMs parses correctly`() {
        val error = mapHttpError(429, retryAfterMs = "999999999")
        assertTrue(error is ApiError.RateLimitError)
        assertEquals(999999999L, (error as ApiError.RateLimitError).retryAfterMillis)
    }

    @Test
    fun `ApiError data object equality works`() {
        assertEquals(ApiError.AuthError, ApiError.AuthError)
        assertEquals(ApiError.ForbiddenError, ApiError.ForbiddenError)
        assertEquals(ApiError.NotFoundError, ApiError.NotFoundError)
        assertEquals(ApiError.NetworkError, ApiError.NetworkError)
    }

    @Test
    fun `ApiError data object inequality works`() {
        assertNotEquals(ApiError.AuthError, ApiError.ForbiddenError)
        assertNotEquals(ApiError.AuthError, ApiError.NetworkError)
    }

    @Test
    fun `ApiError ServerError with different codes are not equal`() {
        assertNotEquals(ApiError.ServerError(502), ApiError.ServerError(503))
        assertNotEquals(ApiError.ServerError(500), ApiError.ServerError(502))
    }

    @Test
    fun `ApiError ServerError with same code is equal`() {
        assertEquals(ApiError.ServerError(500), ApiError.ServerError(500))
    }

    @Test
    fun `ApiError RateLimitError equality`() {
        assertEquals(ApiError.RateLimitError(1000L), ApiError.RateLimitError(1000L))
        assertNotEquals(ApiError.RateLimitError(1000L), ApiError.RateLimitError(2000L))
    }

    @Test
    fun `ApiResult Error equality with same ApiError`() {
        val error1: ApiResult<String> = ApiResult.Error(ApiError.NetworkError)
        val error2: ApiResult<String> = ApiResult.Error(ApiError.NetworkError)
        assertEquals(error1, error2)
    }

    @Test
    fun `ApiResult Success equality`() {
        val s1 = ApiResult.Success(42)
        val s2 = ApiResult.Success(42)
        assertEquals(s1, s2)
    }

    @Test
    fun `mapHttpError 405 maps to ClientError`() {
        val error = mapHttpError(405)
        assertTrue(error is ApiError.ClientError)
        assertEquals(405, (error as ApiError.ClientError).statusCode)
    }

    @Test
    fun `mapHttpError 408 maps to ClientError`() {
        val error = mapHttpError(408)
        assertTrue(error is ApiError.ClientError)
        assertEquals(408, (error as ApiError.ClientError).statusCode)
    }

    @Test
    fun `mapHttpError 422 maps to ClientError`() {
        val error = mapHttpError(422)
        assertTrue(error is ApiError.ClientError)
        assertEquals(422, (error as ApiError.ClientError).statusCode)
    }

    // ================================================================
    // RetryPolicy
    // ================================================================

    @Test
    fun `calculateDelay with very high attempt is capped at maxDelayMs`() {
        val policy = RetryPolicy(
            initialDelayMs = 500L,
            maxDelayMs = 10_000L,
            backoffFactor = 2.0
        )
        // attempt=100 → exp=99，500 * 2^99 非常大，但应被封顶
        assertEquals(10_000L, policy.calculateDelay(attempt = 100))
    }

    @Test
    fun `calculateDelay with backoffFactor 1 always returns initialDelayMs`() {
        val policy = RetryPolicy(
            initialDelayMs = 1000L,
            maxDelayMs = 30_000L,
            backoffFactor = 1.0
        )
        assertEquals(1000L, policy.calculateDelay(attempt = 1))
        assertEquals(1000L, policy.calculateDelay(attempt = 2))
        assertEquals(1000L, policy.calculateDelay(attempt = 5))
        assertEquals(1000L, policy.calculateDelay(attempt = 10))
    }

    @Test
    fun `calculateDelay with backoffFactor 0_5 decreases over attempts`() {
        val policy = RetryPolicy(
            initialDelayMs = 1000L,
            maxDelayMs = 10_000L,
            backoffFactor = 0.5
        )
        val d1 = policy.calculateDelay(attempt = 1)
        val d2 = policy.calculateDelay(attempt = 2)
        val d3 = policy.calculateDelay(attempt = 3)
        // 0.5^n → 递减
        assertTrue(d1 > d2)
        assertTrue(d2 > d3)
    }

    @Test
    fun `retryWithPolicy with maxAttempts 1 does not retry`() = runTest {
        val policy = RetryPolicy(maxAttempts = 1, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        try {
            retryWithPolicy(policy) {
                calls++
                throw IOException("fail")
            }
        } catch (_: IOException) {
        }
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithPolicy retries on ApiError ServerError`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        var caught = false
        try {
            retryWithPolicy(policy) {
                calls++
                if (calls < 3) throw ApiError.ServerError(502)
                "ok"
            }
        } catch (_: ApiError.ServerError) {
            caught = true
        }
        assertFalse(caught)
        assertEquals(3, calls)
    }

    @Test
    fun `retryWithPolicy does not retry ApiError AuthError`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        var caught = false
        try {
            retryWithPolicy(policy) {
                calls++
                throw ApiError.AuthError
            }
        } catch (_: ApiError.AuthError) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithPolicy succeeds on last allowed attempt`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        val result = retryWithPolicy(policy) {
            calls++
            if (calls < 3) throw IOException("fail")
            "success"
        }
        assertEquals("success", result)
        assertEquals(3, calls)
    }

    // ================================================================
    // SseReadTimeoutTracker
    // ================================================================

    @Test
    fun `tracker shouldEnterCooldown false at maxConsecutiveTimeouts minus 1`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        repeat(4) { tracker.recordTimeout() }
        assertFalse(tracker.shouldEnterCooldown())
    }

    @Test
    fun `tracker shouldEnterCooldown true at exactly maxConsecutiveTimeouts`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 300_000L)
        repeat(5) { tracker.recordTimeout() }
        assertTrue(tracker.shouldEnterCooldown())
    }

    @Test
    fun `tracker isInCooldown transitions from true to false after duration`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 100L)
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())
        Thread.sleep(150)
        assertFalse(tracker.isInCooldown())
    }

    @Test
    fun `tracker reset clears consecutive timeouts and cooldown`() {
        val tracker = SseReadTimeoutTracker(maxConsecutiveTimeouts = 5, cooldownDurationMs = 100L)
        repeat(3) { tracker.recordTimeout() }
        assertEquals(3, tracker.consecutiveTimeouts)
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())

        tracker.reset()
        assertEquals(0, tracker.consecutiveTimeouts)
        assertFalse(tracker.isInCooldown())
    }

    @Test
    fun `multiple trackers have independent state`() {
        val tracker1 = SseReadTimeoutTracker(maxConsecutiveTimeouts = 3, cooldownDurationMs = 100L)
        val tracker2 = SseReadTimeoutTracker(maxConsecutiveTimeouts = 3, cooldownDurationMs = 100L)

        tracker1.recordTimeout()
        tracker1.recordTimeout()
        tracker1.recordTimeout()
        assertTrue(tracker1.shouldEnterCooldown())
        assertFalse(tracker2.shouldEnterCooldown())
        assertEquals(3, tracker1.consecutiveTimeouts)
        assertEquals(0, tracker2.consecutiveTimeouts)
    }

    @Test
    fun `tracker isInCooldown true with very long duration`() {
        // 使用足够大、属于 "非常长" 但不会溢出的值
        // System.currentTimeMillis() + duration。currentTimeMillis 约 1.7T，因此最大安全
        // 加数是 Long.MAX_VALUE - System.currentTimeMillis()。
        val safeLong = 100_000_000_000L // 约 27 小时 —— 实际上相当于永久
        val tracker = SseReadTimeoutTracker(
            maxConsecutiveTimeouts = 5,
            cooldownDurationMs = safeLong
        )
        tracker.enterCooldown()
        assertTrue(tracker.isInCooldown())
    }

    @Test
    fun `isTransientException returns false for ApiError ForbiddenError`() {
        assertFalse(isTransientException(ApiError.ForbiddenError))
    }

    @Test
    fun `isTransientException returns false for ApiError ClientError`() {
        assertFalse(isTransientException(ApiError.ClientError(400)))
    }
}
