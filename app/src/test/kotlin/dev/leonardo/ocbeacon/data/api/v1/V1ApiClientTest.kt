package dev.leonardo.ocbeacon.data.api.v1

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * V1ApiClient 端点测试（#87 回归）：
 * - listMessages 非 2xx（404 会话不存在）→ 返回空页而非 JsonConvertException
 *   （旧代码把 404 JSON 错误体按 List 解析 → 压测实测 302 次异常刷日志）
 */
class V1ApiClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val v1Conn = ServerConnection(
        baseUrl = "http://test-v1.local",
        authHeader = "Basic dGVzdDp0ZXN0",
        apiVersion = ApiVersion.V1
    )

    private fun buildClient(engine: MockEngine): V1ApiClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return V1ApiClient(ApiClient(httpClient, json))
    }

    @Test
    fun `listMessages returns empty page on 404 instead of JsonConvertException`() = runTest {
        // 回归（#87）：L2 stale 轮询已删除会话 → 404 错误体 {"name":"NotFoundError",...}
        // 旧代码 body<List>() 解析对象 → JsonConvertException（每 5 秒一次，302 次/25 分钟）
        val engine = MockEngine { request ->
            assertEquals("/session/ses_gone/message", request.url.encodedPath)
            respond(
                """{"name":"NotFoundError","data":{"message":"Session not found: ses_gone"}}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }
        val api = buildClient(engine)
        val page = api.listMessages(v1Conn, "ses_gone", limit = 50)
        assertTrue("404 应返回空消息列表", page.messages.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `listMessages returns empty page on server error`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"name":"UnknownError","data":{"message":"boom"}}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }
        val api = buildClient(engine)
        val page = api.listMessages(v1Conn, "ses_x", limit = 50)
        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun `listMessages parses array response normally`() = runTest {
        val body = """[{"info":{"id":"msg_1","sessionID":"ses_1","role":"user","time":{"created":1000}},"parts":[]}]"""
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val page = api.listMessages(v1Conn, "ses_1", limit = 50)
        assertEquals(1, page.messages.size)
    }
}
