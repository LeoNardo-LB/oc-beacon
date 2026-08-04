package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.ApiError
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.math.pow

/**
 * 指数退避重试行为的配置。
 *
 * @param maxAttempts     最大尝试次数（包含首次调用）。
 * @param initialDelayMs  首次重试前的延迟。
 * @param maxDelayMs      最大延迟上限。
 * @param backoffFactor   重试之间的倍数（例如 2.0 = 每次翻倍）。
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500L,
    val maxDelayMs: Long = 10_000L,
    val backoffFactor: Double = 2.0
) {
    /**
     * 计算给定 [attempt]（从 1 开始）的延迟。
     * attempt=1 → initialDelay，attempt=2 → initialDelay*factor，以此类推。
     */
    fun calculateDelay(attempt: Int): Long {
        val exp = (attempt - 1).coerceAtLeast(0)
        val delay = (initialDelayMs * backoffFactor.pow(exp.toDouble())).toLong()
        return min(delay, maxDelayMs)
    }
}

/**
 * 判断异常是否为瞬时错误、值得重试。
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
 * 按 [policy] 重试执行 [block]。
 *
 * - 仅在瞬时错误（[IOException]、[SocketTimeoutException]、
 *   `isTransient=true` 的 [ApiError]）时重试。
 * - 非瞬时异常立即传播。
 * - 所有重试用尽后，重新抛出最后一个异常。
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
