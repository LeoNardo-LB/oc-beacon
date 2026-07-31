package dev.leonardo.octether.data.api

import dev.leonardo.octether.domain.model.ApiError
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.math.pow

/**
 * Configuration for exponential backoff retry behavior.
 *
 * @param maxAttempts     Maximum number of attempts (including the first call).
 * @param initialDelayMs  Delay before the first retry.
 * @param maxDelayMs      Maximum delay cap.
 * @param backoffFactor   Multiplier between retries (e.g. 2.0 = double each time).
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500L,
    val maxDelayMs: Long = 10_000L,
    val backoffFactor: Double = 2.0
) {
    /**
     * Calculate the delay for a given [attempt] (1-based).
     * attempt=1 → initialDelay, attempt=2 → initialDelay*factor, etc.
     */
    fun calculateDelay(attempt: Int): Long {
        val exp = (attempt - 1).coerceAtLeast(0)
        val delay = (initialDelayMs * backoffFactor.pow(exp.toDouble())).toLong()
        return min(delay, maxDelayMs)
    }
}

/**
 * Whether an exception is transient and worth retrying.
 */
fun isTransientException(throwable: Throwable): Boolean {
    return when (throwable) {
        is IOException -> true
        is SocketTimeoutException -> true
        is ApiError -> throwable.isTransient
        else -> false
    }
}

/**
 * Execute [block] with retry according to [policy].
 *
 * - Retries only on transient errors ([IOException], [SocketTimeoutException],
 *   [ApiError] with `isTransient=true`).
 * - Non-transient exceptions propagate immediately.
 * - After all retries exhausted, the last exception is re-thrown.
 */
suspend fun <T> retryWithPolicy(policy: RetryPolicy, block: suspend () -> T): T {
    var lastException: Throwable? = null
    repeat(policy.maxAttempts) { index ->
        try {
            return block()
        } catch (e: Throwable) {
            lastException = e
            if (!isTransientException(e)) throw e
            if (index < policy.maxAttempts - 1) {
                delay(policy.calculateDelay(index + 1))
            }
        }
    }
    throw lastException ?: IllegalStateException("retryWithPolicy: no exception captured")
}
