package dev.leonardo.ocbeacon.domain.model

/**
 * 所有 API 操作的统一结果类型。
 * 替代 boolean 返回值、抛异常、Result<T> 混用的方案。
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
 * 带 HTTP 状态码分类的类型化 API 错误。
 */
sealed class ApiError : Exception() {
    /** 认证失败（401）。 */
    data object AuthError : ApiError()

    /** 授权失败（403）。 */
    data object ForbiddenError : ApiError()

    /** 资源未找到（404）。 */
    data object NotFoundError : ApiError()

    /** 被限流（429）。[retryAfterMillis] 取自 retry-after / retry-after-ms 响应头。 */
    data class RateLimitError(val retryAfterMillis: Long = 0L) : ApiError()

    /** 服务端错误（5xx）。 */
    data class ServerError(val statusCode: Int) : ApiError()

    /** 客户端错误（4xx，不含已分类的类型）。 */
    data class ClientError(val statusCode: Int) : ApiError()

    /** 网络层失败（无响应、IOException、超时）。 */
    data object NetworkError : ApiError()

    /** 该错误是否为瞬时错误、值得重试。 */
    val isTransient: Boolean
        get() = when (this) {
            is ServerError -> true
            is RateLimitError -> true
            is NetworkError -> true
            else -> false
        }
}

/**
 * 将 HTTP 状态码（及可选的限流响应头）映射为 [ApiError]。
 *
 * @param statusCode HTTP 状态码。
 * @param retryAfterSeconds `retry-after` 响应头的值（秒）。
 * @param retryAfterMs `retry-after-ms` 响应头的值（毫秒）。
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
