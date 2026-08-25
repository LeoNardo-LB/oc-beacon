package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.ApiError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * C8（2026-08-26）：Ktor → ApiError 分类学翻译测试（死代码复活接线）。
 *
 * 两层验证：
 * 1. MockEngine 集成（expectSuccess=true，非 2xx 抛 Ktor 异常）→ apiCall 边缘翻译
 *    覆盖 401/429/500 三个代表性状态码 + retry-after 头解析；
 * 2. 传输层异常直译（超时/连接超时/IO）+ 未知异常非瞬时 + 成功直通 + 取消不吞。
 */
class ApiErrorTranslatorTest {

    /** expectSuccess=true 的 MockEngine 客户端——非 2xx 由 Ktor 抛 ClientRequest/ServerResponseException。 */
    private fun mockClient(status: HttpStatusCode, headers: Headers = Headers.Empty): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = true
            engine {
                addHandler {
                    respond("{}", status = status, headers = headers)
                }
            }
        }

    private fun <T> translateOf(block: suspend () -> T): ApiError = try {
        runBlocking { apiCall("TestApi", "test-ctx", block) }
        throw AssertionError("expected apiCall to throw")
    } catch (e: ApiError) {
        e
    }

    private suspend fun getBody(client: HttpClient): String = client.get("http://test/api").bodyAsText()

    // ---- MockEngine 4xx/5xx → taxonomy ----

    @Test
    fun `mock 401 translates to AuthError`() {
        val error = translateOf { getBody(mockClient(HttpStatusCode.Unauthorized)) }
        assertTrue("expected AuthError but was $error", error is ApiError.AuthError)
        assertFalse(error.isTransient)
    }

    @Test
    fun `mock 429 translates to RateLimitError with retry-after`() {
        val error = translateOf {
            getBody(mockClient(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter to listOf("2"))))
        }
        assertTrue("expected RateLimitError but was $error", error is ApiError.RateLimitError)
        assertEquals(2_000L, (error as ApiError.RateLimitError).retryAfterMillis)
        assertTrue(error.isTransient)
    }

    @Test
    fun `mock 500 translates to ServerError`() {
        val error = translateOf { getBody(mockClient(HttpStatusCode.InternalServerError)) }
        assertTrue("expected ServerError but was $error", error is ApiError.ServerError)
        assertEquals(500, (error as ApiError.ServerError).statusCode)
        assertTrue(error.isTransient)
    }

    @Test
    fun `mock 403 and 404 map to Forbidden and NotFound`() {
        val forbidden = translateOf { getBody(mockClient(HttpStatusCode.Forbidden)) }
        val notFound = translateOf { getBody(mockClient(HttpStatusCode.NotFound)) }
        assertTrue(forbidden is ApiError.ForbiddenError)
        assertTrue(notFound is ApiError.NotFoundError)
    }

    // ---- 传输层异常直译 ----

    @Test
    fun `request timeout translates to NetworkError`() {
        val timeout = HttpRequestTimeoutException(io.ktor.client.request.HttpRequestBuilder())
        assertEquals(ApiError.NetworkError, timeout.asApiError())
        assertTrue(timeout.asApiError().isTransient)
    }

    @Test
    fun `connect timeout translates to NetworkError`() {
        assertEquals(ApiError.NetworkError, ConnectTimeoutException("connect timed out").asApiError())
    }

    @Test
    fun `plain IOException translates to NetworkError`() {
        assertEquals(ApiError.NetworkError, IOException("reset").asApiError())
    }

    @Test
    fun `unknown exception maps to non-transient ClientError`() {
        // 序列化/断言等未知异常不冒充网络错误（不参与 isTransient 重试判定）
        val error = RuntimeException("parse boom").asApiError()
        assertTrue(error is ApiError.ClientError)
        assertFalse(error.isTransient)
    }

    @Test
    fun `apiError passthrough is idempotent`() {
        val original = ApiError.ServerError(503)
        assertEquals(original, original.asApiError())
    }

    // ---- apiCall 语义 ----

    @Test
    fun `apiCall passes success value through`() = runBlocking {
        val value = apiCall("TestApi", "ok-ctx") { 42 }
        assertEquals(42, value)
    }

    @Test
    fun `apiCall rethrows CancellationException untranslated`() {
        try {
            runBlocking {
                apiCall("TestApi", "cancel-ctx") { throw CancellationException("cancelled") }
            }
        } catch (e: CancellationException) {
            // 原样重抛（协程取消不是错误）——不能被翻译成 ApiError 吞掉取消信号
        }
    }

    @Test
    fun `non-2xx response maps via toApiError without throwing`() = runBlocking {
        // 生产 HttpClient 未开 expectSuccess：非 2xx 不抛，由调用点显式 toApiError
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { respond("{}", status = HttpStatusCode.NotFound) }
            }
        }
        val response = client.get("http://test/api")
        assertEquals(ApiError.NotFoundError, response.toApiError())
        client.close()
    }
}
