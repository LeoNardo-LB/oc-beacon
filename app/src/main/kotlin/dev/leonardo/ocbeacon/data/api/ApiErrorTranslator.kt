package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.ApiError
import dev.leonardo.ocbeacon.domain.model.mapHttpError
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Ktor → [ApiError] 分类学统一边缘翻译（C8 死代码复活接线，2026-08-26 架构走查）。
 *
 * 先例：GitHubApiClient.asGitHubError 的 recoverCatching { throw e.asGitHubError() }
 * 边缘翻译模式。domain/model/ApiResult.kt 的完整 taxonomy（AuthError/ForbiddenError/
 * NotFoundError/RateLimitError/ServerError/ClientError/NetworkError + isTransient）与
 * data/api/RetryPolicy.kt（retryWithPolicy）此前 0 生产调用；本翻译器把 taxonomy 接到
 * data 层 HTTP 边缘：
 * - 传输层异常（[HttpRequestTimeoutException]/[ConnectTimeoutException]/[IOException]）
 *   → [ApiError.NetworkError]（isTransient=true，与 RetryPolicy.isTransientException 对齐）
 * - [ClientRequestException]（4xx，expectSuccess 打开时抛出）→ [mapHttpError]
 *   （401/403/404/429 精确分类，429 解析 retry-after 头）
 * - [ServerResponseException]（5xx）→ [ApiError.ServerError]
 * - 非 2xx 响应（本客户端未开 expectSuccess，Ktor 不抛）→ [HttpResponse.toApiError] 显式映射
 *
 * 渐进策略（本批）：只做分类日志（[logApiError]）与异常翻译后按原语义失败/返回，
 * 不改 44 个 Boolean 方法签名；后续按域逐个接入 ApiResult 返回值。
 */

/**
 * Ktor 异常 → [ApiError]。已是 [ApiError] 则原样返回（幂等）。
 * 未知异常（序列化/断言等）→ [ApiError.ClientError]（statusCode=0，非瞬时——
 * 不冒充网络错误参与 isTransient 重试判定）。
 */
fun Throwable.asApiError(): ApiError = when (this) {
    is ApiError -> this
    is ClientRequestException -> mapHttpError(
        statusCode = response.status.value,
        retryAfterSeconds = response.headers["retry-after"],
        retryAfterMs = response.headers["retry-after-ms"],
    )
    is ServerResponseException -> ApiError.ServerError(response.status.value)
    is HttpRequestTimeoutException, is ConnectTimeoutException -> ApiError.NetworkError
    is IOException -> ApiError.NetworkError
    else -> ApiError.ClientError(0)
}

/**
 * 非 2xx 响应 → [ApiError]（本 HttpClient 未开 expectSuccess，非 2xx 不抛异常，
 * 由调用点在 status 检查处显式映射）。429 解析 retry-after / retry-after-ms 头。
 */
fun HttpResponse.toApiError(): ApiError = mapHttpError(
    statusCode = status.value,
    retryAfterSeconds = headers["retry-after"],
    retryAfterMs = headers["retry-after-ms"],
)

/**
 * 分类日志（AppLogger，会进应用内 Diagnostics 屏幕）：taxonomy 类名 + 关键参数 +
 * isTransient + 调用上下文。瞬时错误（服务器/限流/网络）W 级——重试可预期的噪声；
 * 非瞬时（认证/授权/404/解析）E 级。cause 保留原始异常栈（可观测性）。
 */
fun logApiError(tag: String, error: ApiError, context: String, cause: Throwable? = null) {
    val detail = when (error) {
        is ApiError.RateLimitError -> "(retryAfterMs=${error.retryAfterMillis})"
        is ApiError.ServerError -> "(status=${error.statusCode})"
        is ApiError.ClientError -> "(status=${error.statusCode})"
        else -> ""
    }
    val line = "[api-error] ${error::class.simpleName}$detail transient=${error.isTransient} $context"
    val level: (String, String, Throwable?) -> Unit =
        if (error.isTransient) { t, m, c -> if (c != null) AppLogger.w(t, m, c) else AppLogger.w(t, m) }
        else { t, m, c -> if (c != null) AppLogger.e(t, m, c) else AppLogger.e(t, m) }
    level(tag, line, cause)
}

/**
 * 统一边缘执行：[block] 抛出的 Ktor 异常翻译为 [ApiError]、记分类日志后**重抛翻译
 * 结果**（GitHub asGitHubError 同款形状——调用方 runCatching 捕获的是 taxonomy 错误，
 * 可按 isTransient 分支）。[CancellationException] 原样重抛（协程取消不是错误）。
 */
suspend fun <T> apiCall(tag: String, context: String, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val translated = e.asApiError()
        logApiError(tag, translated, context, e)
        throw translated
    }
}
