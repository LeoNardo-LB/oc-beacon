package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.ApiError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RetryPolicyTest {

    // ============ RetryPolicy defaults ============

    @Test
    fun `default policy has expected values`() {
        val policy = RetryPolicy()
        assertEquals(3, policy.maxAttempts)
        assertEquals(500L, policy.initialDelayMs)
        assertEquals(10_000L, policy.maxDelayMs)
        assertEquals(2.0, policy.backoffFactor, 0.01)
    }

    // ============ calculateDelay ============

    @Test
    fun `calculateDelay for first attempt returns initialDelay`() {
        val policy = RetryPolicy()
        assertEquals(500L, policy.calculateDelay(attempt = 1))
    }

    @Test
    fun `calculateDelay for second attempt doubles`() {
        val policy = RetryPolicy()
        assertEquals(1000L, policy.calculateDelay(attempt = 2))
    }

    @Test
    fun `calculateDelay is capped at maxDelay`() {
        val policy = RetryPolicy(initialDelayMs = 1000L, maxDelayMs = 2000L, backoffFactor = 10.0)
        assertEquals(2000L, policy.calculateDelay(attempt = 3))
    }

    @Test
    fun `calculateDelay for attempt 0 returns initialDelay`() {
        val policy = RetryPolicy()
        assertEquals(500L, policy.calculateDelay(attempt = 0))
    }

    // ============ isTransientException ============

    @Test
    fun `IOException is transient`() {
        assertTrue(isTransientException(IOException("connection reset")))
    }

    @Test
    fun `SocketTimeoutException is transient`() {
        assertTrue(isTransientException(SocketTimeoutException("timeout")))
    }

    @Test
    fun `ApiError ServerError is transient`() {
        assertTrue(isTransientException(ApiError.ServerError(500)))
    }

    @Test
    fun `ApiError RateLimitError is transient`() {
        assertTrue(isTransientException(ApiError.RateLimitError(1000L)))
    }

    @Test
    fun `ApiError NetworkError is transient`() {
        assertTrue(isTransientException(ApiError.NetworkError))
    }

    @Test
    fun `ApiError AuthError is not transient`() {
        assertTrue(!isTransientException(ApiError.AuthError))
    }

    @Test
    fun `RuntimeException is not transient`() {
        assertTrue(!isTransientException(RuntimeException("bug")))
    }

    // ============ retryWithPolicy success path ============

    @Test
    fun `retryWithPolicy returns success on first attempt`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        val result = retryWithPolicy(policy) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithPolicy retries on IOException and succeeds`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        val result = retryWithPolicy(policy) {
            calls++
            if (calls < 3) throw IOException("fail")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `retryWithPolicy throws after exhausting retries`() = runTest {
        val policy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        var caught = false
        try {
            retryWithPolicy(policy) {
                calls++
                throw IOException("persistent failure")
            }
        } catch (e: IOException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(2, calls)
    }

    @Test
    fun `retryWithPolicy does not retry non-transient exception`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayMs = 0)
        var calls = 0
        var caught = false
        try {
            retryWithPolicy(policy) {
                calls++
                throw RuntimeException("bug")
            }
        } catch (e: RuntimeException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(1, calls)
    }
}
