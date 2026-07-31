package dev.leonardo.octether.domain.model

/**
 * Unified result type for all API operations.
 * Replaces the mix of boolean returns, thrown exceptions, and Result<T>.
 */
sealed class ApiResult<T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error<T>(val error: ApiError) : ApiResult<T>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun getOrDefault(default: T): T = when (this) {
        is Success -> data
        is Error -> default
    }
}

/**
 * Typed API errors with HTTP status code classification.
 */
sealed class ApiError : Exception() {
    /** Authentication failure (401). */
    data object AuthError : ApiError()

    /** Authorization failure (403). */
    data object ForbiddenError : ApiError()

    /** Resource not found (404). */
    data object NotFoundError : ApiError()

    /** Rate limited (429). [retryAfterMillis] from retry-after / retry-after-ms header. */
    data class RateLimitError(val retryAfterMillis: Long = 0L) : ApiError()

    /** Server-side error (5xx). */
    data class ServerError(val statusCode: Int) : ApiError()

    /** Client-side error (4xx, excluding classified ones). */
    data class ClientError(val statusCode: Int) : ApiError()

    /** Network-level failure (no response, IOException, timeout). */
    data object NetworkError : ApiError()

    /** Whether this error is transient and worth retrying. */
    val isTransient: Boolean
        get() = when (this) {
            is ServerError -> true
            is RateLimitError -> true
            is NetworkError -> true
            else -> false
        }
}

/**
 * Map an HTTP status code (and optional rate-limit headers) to an [ApiError].
 *
 * @param statusCode The HTTP status code.
 * @param retryAfterSeconds Value of `retry-after` response header (seconds).
 * @param retryAfterMs Value of `retry-after-ms` response header (milliseconds).
 */
fun mapHttpError(
    statusCode: Int,
    retryAfterSeconds: String? = null,
    retryAfterMs: String? = null
): ApiError = when (statusCode) {
    401 -> ApiError.AuthError
    403 -> ApiError.ForbiddenError
    404 -> ApiError.NotFoundError
    429 -> {
        val millis = retryAfterMs?.toLongOrNull()
            ?: retryAfterSeconds?.toLongOrNull()?.times(1000L)
            ?: 0L
        ApiError.RateLimitError(millis)
    }
    in 500..599 -> ApiError.ServerError(statusCode)
    else -> ApiError.ClientError(statusCode)
}
