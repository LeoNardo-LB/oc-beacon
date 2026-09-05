package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #322：DSH 服务端内容搜索（session/search）wire 契约测试。
 *
 * 契约钉死源 dsh-api-session-controller types.d.ts（SessionSearchRequest/Value +
 * SESSION_SEARCH_RESULT_LIMIT=20 / SESSION_SEARCH_SNIPPET_MAX_CODE_POINTS=240）：
 * - 请求 {query}（0.1.2 wire：{args:{request:{query}}}——typert parameter wire="request"）；
 * - 回值 {items:[{sessionId,snippet}],hasMore}（上限 20 会话）。
 *
 * 线面版本：V012 专属（0.1.1 52 方法面无 session.search——保守 unsupported）。
 */
class DshApiClientSessionSearchTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val conn = ServerConnection(
        baseUrl = "http://dsh-test.local",
        authHeader = null,
        apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V1,
        serverType = ServerType.Dsh,
    )

    private fun client(
        engine: MockEngine,
        protocol: DshWireProtocol? = null,
    ): DshApiClient {
        val registry = mockk<DshConnectionRegistry>(relaxed = true)
        every { registry.protocolOf(any()) } returns protocol
        every { registry.cookieHeader(any()) } returns null
        return DshApiClient(
            DshRpcClient(ApiClient(HttpClient(engine), json), registry),
            FixedProtocolSource(protocol),
        )
    }

    private class FixedProtocolSource(private val protocol: DshWireProtocol?) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
    }

    private fun ok(value: String) =
        "{\"type\":\"server-response\",\"rpcId\":\"r\",\"result\":{\"ok\":true,\"value\":" + value + "}}"

    private fun jsonHeaders() = headersOf("Content-Type" to listOf("application/json"))

    private fun bodyTextOf(request: io.ktor.client.request.HttpRequestData): String =
        (request.body as TextContent).text

    @Test
    fun `v012 searchSessions posts session search with query and maps items`() = runTest {
        val engine = MockEngine {
            respond(
                ok("{\"items\":[{\"sessionId\":\"s-1\",\"snippet\":\"fix the login flow\"},{\"sessionId\":\"s-2\",\"snippet\":\"retry banner\"}],\"hasMore\":true}"),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).searchSessions(conn, "login")
        assertEquals(listOf("s-1", "s-2"), result.items.map { it.sessionId })
        assertEquals("fix the login flow", result.items[0].snippet)
        assertEquals("retry banner", result.items[1].snippet)
        assertTrue(result.hasMore)
        // wire 形态：POST /api/session/search；信封 method 同名（P-4）；args.request.query
        val req = engine.requestHistory.single()
        assertEquals("/api/session/search", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session/search", body["method"]!!.jsonPrimitive.content)
        val request = body["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("login", request["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v012 searchSessions empty items and hasMore false`() = runTest {
        val engine = MockEngine {
            respond(ok("{\"items\":[],\"hasMore\":false}"), HttpStatusCode.OK, jsonHeaders())
        }
        val result = client(engine, DshWireProtocol.V012).searchSessions(conn, "nothing-matches")
        assertTrue(result.items.isEmpty())
        assertTrue(!result.hasMore)
    }

    @Test
    fun `v012 searchSessions skips malformed rows and defaults hasMore to false`() = runTest {
        // 畸形行容错：非对象行 / sessionId 缺席或空白 / snippet 缺席——逐行跳过不整批失败；
        // hasMore 缺席按 false（服务器契约恒回该键，缺席即畸形——保守无更多）。
        val engine = MockEngine {
            respond(
                ok("{\"items\":[\"bare-string\",{\"snippet\":\"no-session-id\"},{\"sessionId\":\"\",\"snippet\":\"blank-id\"},{\"sessionId\":\"s-7\"},{\"sessionId\":\"s-8\",\"snippet\":\"valid\"}]}"),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).searchSessions(conn, "kw")
        assertEquals(listOf("s-8"), result.items.map { it.sessionId })
        assertEquals("valid", result.items[0].snippet)
        assertTrue(!result.hasMore)
    }

    @Test
    fun `v011 searchSessions throws unsupported without request`() = runTest {
        // 未探测（null → 保守 V011）与显式 V011 均不发力——0.1.1 方法面无 session.search
        for (protocol in listOf(null, DshWireProtocol.V011)) {
            val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
            val ex = runCatching { client(engine, protocol).searchSessions(conn, "kw") }.exceptionOrNull()
            assertTrue("protocol=" + protocol + " expected UnsupportedServerCapability", ex is UnsupportedServerCapability)
            assertTrue(engine.requestHistory.isEmpty()) // 不发请求
        }
    }

    @Test
    fun `v012 searchSessions truncates snippet over 240 code points`() = runTest {
        // 契约 snippet ≤240 码点（服务器保证）；客户端防御性截断——超长不透传整段。
        // 代理对边界：emoji 按 240 码点截断不得劈开代理对（240 码点 = 480 UTF-16 单元）。
        val longAscii = "x".repeat(300)
        val longEmoji = "😀".repeat(250)
        val engine = MockEngine {
            respond(
                ok("{\"items\":[{\"sessionId\":\"s-a\",\"snippet\":\"" + longAscii + "\"},{\"sessionId\":\"s-b\",\"snippet\":\"" + longEmoji + "\"}],\"hasMore\":false}"),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).searchSessions(conn, "kw")
        assertEquals(240, result.items[0].snippet.length)
        assertEquals(240, result.items[0].snippet.codePointCount(0, result.items[0].snippet.length))
        assertEquals(240, result.items[1].snippet.codePointCount(0, result.items[1].snippet.length))
        assertEquals(480, result.items[1].snippet.length)
    }
}
