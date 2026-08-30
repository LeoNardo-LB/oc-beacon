package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * DshRpcClient 传输契约测试（backlog #274 组件 ③；设计文档 §1.6/§5）。
 *
 * MockEngine 真实执行请求/响应周期，验证：
 * - POST {baseUrl}/api/{method}，method 同时出现在 URL 路径与 body（P-4 信封铁律）
 * - Content-Type application/json；**不设 Origin**；忽略 conn.authHeader（DSH 无鉴权）
 * - HTTP 200 → ServerResponse 解析（ok 值 transform / error 码映射）
 * - 非 200（415/400/404/403/426/500）→ DshApiError(httpStatus=…)
 * - respond 回程 POST /api/respond（ClientResponse 信封，rpcId 稳定复用）
 */
class DshRpcClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // authHeader 故意携带：DSH 无鉴权，客户端必须忽略它（也不设 Origin）
    private val conn = ServerConnection(baseUrl = "http://dsh-test.local/", authHeader = "Basic dGVzdDp0ZXN0")

    private fun client(engine: MockEngine): DshRpcClient =
        DshRpcClient(ApiClient(HttpClient(engine), json))

    private val sessionListOk = """
        {"type":"server-response","rpcId":"ignored","result":{"ok":true,"value":{"items":[
            {"sessionId":"s-1","updatedAt":1,"running":false,"blank":false},
            {"sessionId":"s-2","updatedAt":2,"running":true,"blank":false}
        ]}}}
    """.trimIndent()


    @Test
    fun `call posts envelope with method in both url path and body`() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            assertEquals("/api/session.list", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            // Ktor 把 Content-Type 挪到 OutgoingContent 上（MockEngine 边界 headers 里只剩 Accept）
            val contentType = request.headers["Content-Type"]
                ?: (request.body as TextContent).contentType.toString()
            assertTrue("Content-Type 必须是 application/json，实际=" + contentType, contentType.startsWith("application/json"))
            assertNull("DSH 无鉴权：Authorization 头必须缺席", request.headers["Authorization"])
            assertNull("非浏览器客户端：Origin 头必须缺席（§1.6-4）", request.headers["Origin"])
            capturedBody = (request.body as TextContent).text
            respond(sessionListOk, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        val result = client(engine).call(conn, "session.list", buildJsonObject {}) { value ->
            value["items"]!!.jsonArray.size
        }
        assertEquals(2, result.getOrNull())
        // P-4 信封铁律：body.method 与 URL 路径段一致；payload 原样透传
        val envelope = DshEnvelope.decode(capturedBody!!) as DshEnvelope.ClientRequest
        assertEquals("session.list", envelope.method)
        assertEquals(buildJsonObject {}, envelope.payload)
        assertTrue(envelope.rpcId.isNotEmpty())
    }

    @Test
    fun `call business error maps to DshApiError with code and httpStatus 200`() = runTest {
        val engine = MockEngine { request ->
            respond(
                """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":{"code":"internal","message":"search index disabled","details":{}}}}""",
                HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")),
            )
        }
        val result = client(engine).call(conn, "session.search", buildJsonObject { put("q", "x") }) { it }
        val error = result.exceptionOrNull() as DshApiError
        assertEquals("internal", error.code?.wire)
        assertEquals(200, error.httpStatus)
        assertEquals("search index disabled", error.message)
        assertEquals(DshErrorCategory.Server, error.category)
    }

    @Test
    fun `call non-200 500 maps to http status error without code`() = runTest {
        val engine = MockEngine { request -> respond("boom", HttpStatusCode.InternalServerError) }
        val result = client(engine).call(conn, "session.list", buildJsonObject {}) { it }
        val error = result.exceptionOrNull() as DshApiError
        assertNull(error.code)
        assertEquals(500, error.httpStatus)
        assertEquals(DshErrorCategory.Server, error.category)
        assertTrue(error.message.contains("500"))
    }

    @Test
    fun `call 415 content type error maps to Unknown`() = runTest {
        val engine = MockEngine { request -> respond("unsupported media type", HttpStatusCode.UnsupportedMediaType) }
        val result = client(engine).call(conn, "session.list", buildJsonObject {}) { it }
        val error = result.exceptionOrNull() as DshApiError
        assertEquals(415, error.httpStatus)
        assertEquals(DshErrorCategory.Unknown, error.category)
    }

    @Test
    fun `call malformed 200 envelope fails gracefully without throwing`() = runTest {
        val engine = MockEngine { request -> respond("<html>not json</html>", HttpStatusCode.OK) }
        val result = client(engine).call(conn, "session.list", buildJsonObject {}) { it }
        val error = result.exceptionOrNull() as DshApiError
        assertNull(error.code)
        assertEquals(200, error.httpStatus)
        assertEquals(DshErrorCategory.Unknown, error.category)
    }

    @Test
    fun `call transport failure maps to Network category with cause`() = runTest {
        val engine = MockEngine { request -> throw IOException("connection refused") }
        val result = client(engine).call(conn, "session.list", buildJsonObject {}) { it }
        val error = result.exceptionOrNull() as DshApiError
        assertNull(error.code)
        assertNull(error.httpStatus)
        assertEquals(DshErrorCategory.Network, error.category)
        assertNotNull(error.cause)
    }

    @Test
    fun `ok response with non-object value is a shape failure`() = runTest {
        // 52 方法面 value 恒对象（P-4）；若服务端形态漂移，显式失败而非静默误读
        val engine = MockEngine { request ->
            respond("""{"type":"server-response","rpcId":"x","result":{"ok":true,"value":42}}""",
                HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        val result = client(engine).call(conn, "session.list", buildJsonObject {}) { it }
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as DshApiError
        assertEquals(DshErrorCategory.Unknown, error.category)
    }

    // ============ respond 回程 ============

    @Test
    fun `respond posts client-response envelope to api respond path`() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            assertEquals("/api/respond", request.url.encodedPath)
            capturedBody = (request.body as TextContent).text
            respond(
                """{"type":"server-response","rpcId":"ignored","result":{"ok":true,"value":{"accepted":true}}}""",
                HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")),
            )
        }
        val outcome = buildJsonObject { put("outcome", "allowed-once") }
        val result = client(engine).respond(conn, "11111111-0000-0000-0000-000000000004", outcome)
        assertEquals(Unit, result.getOrNull())
        val envelope = DshEnvelope.decode(capturedBody!!) as DshEnvelope.ClientResponse
        assertEquals("11111111-0000-0000-0000-000000000004", envelope.rpcId)
        val ok = envelope.result as DshRpcResult.Ok
        assertEquals(outcome, ok.value!!.jsonObject)
    }

    @Test
    fun `respond error receipt maps to DshApiError keeping unknown code`() = runTest {
        val engine = MockEngine { request ->
            respond(
                """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":{"code":"not-pending","message":"no pending request","details":{}}}}""",
                HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")),
            )
        }
        val result = client(engine).respond(conn, "rpc-stable-1", buildJsonObject { put("outcome", "denied") })
        val error = result.exceptionOrNull() as DshApiError
        assertEquals("not-pending", error.code?.wire)
        assertFalse(error.code!!.isKnown)
        assertEquals(DshErrorCategory.Unknown, error.category)
    }
}
