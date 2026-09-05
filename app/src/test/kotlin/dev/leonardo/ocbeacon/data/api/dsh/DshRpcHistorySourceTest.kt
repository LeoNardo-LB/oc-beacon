package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DshRpcHistorySource] 载荷契约测试（#310① A8 缺陷A——对账/回填 history 腿）。
 *
 * 服务器契约（session-controller index.js validateAddress）：origin=subagent 会话
 * 拒收 {kind:session} 地址（"subagent Sessions require their durable parent
 * address"）；V012 page 须按 session.list 行装配 {kind:subagent,parentSessionId,
 * childSessionId,mode} durable 地址（mode 与 subagent 投影身份严格一致）。
 * MockEngine 走真实 DshRpcClient（DshApiClientTest 同款纪律）。
 */
class DshRpcHistorySourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val conn = ServerConnection(
        baseUrl = "http://dsh-test.local",
        authHeader = null,
        apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V1,
        serverType = dev.leonardo.ocbeacon.domain.model.ServerType.Dsh,
    )

    private fun ok(value: String): String =
        "{\"type\":\"server-response\",\"rpcId\":\"r\",\"result\":{\"ok\":true,\"value\":" + value + "}}"

    private fun headers() = headersOf("Content-Type" to listOf("application/json"))

    private fun source(engine: MockEngine, protocol: DshWireProtocol): DshRpcHistorySource {
        val registry = io.mockk.mockk<DshConnectionRegistry>(relaxed = true)
        io.mockk.every { registry.protocolOf(any()) } returns protocol
        io.mockk.every { registry.cookieHeader(any()) } returns null
        return DshRpcHistorySource(
            DshRpcClient(ApiClient(HttpClient(engine), json), registry),
            conn,
        ) { protocol }
    }

    /** 子会话行（session.list 实测形状：parentSessionId + origin + subagent 投影身份）。 */
    private val subagentListValue = """{"items":[
        {"sessionId":"s-child","updatedAt":1788109000023,"running":true,"blank":false,
         "cwd":"/w","parentSessionId":"s-parent","origin":"subagent",
         "projections":{"asOfSeq":9,"values":{"subagent":{"mode":"continuable","label":"counter"}}}}
    ]}""".trimIndent()

    private val ordinaryListValue = """{"items":[
        {"sessionId":"s-1","updatedAt":1788109000023,"running":false,"blank":false,
         "cwd":"/w","projections":{"asOfSeq":42,"values":{}}}
    ]}""".trimIndent()

    @Test
    fun `v012 subagent child fetches page via durable subagent address`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/list" -> respond(ok(subagentListValue), HttpStatusCode.OK, headers())
                "/api/session/page" -> respond(ok("""{"records":[],"hasMore":false}"""), HttpStatusCode.OK, headers())
                else -> error("unexpected " + req.url.encodedPath)
            }
        }
        source(engine, DshWireProtocol.V012).fetchPage("s-child", beforeSeq = null, maxMessages = 50)
        val pageReq = json.parseToJsonElement(
            (captureBodies(engine).last())
        ).jsonObject
        assertEquals(
            """{"args":{"request":{"address":{"kind":"subagent","parentSessionId":"s-parent","childSessionId":"s-child","mode":"continuable"},"throughSeq":9,"maxMessages":50}}}""",
            pageReq["payload"].toString(),
        )
    }

    @Test
    fun `v012 ordinary session keeps session address`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/list" -> respond(ok(ordinaryListValue), HttpStatusCode.OK, headers())
                "/api/session/page" -> respond(ok("""{"records":[],"hasMore":false}"""), HttpStatusCode.OK, headers())
                else -> error("unexpected " + req.url.encodedPath)
            }
        }
        source(engine, DshWireProtocol.V012).fetchPage("s-1", beforeSeq = 7, maxMessages = 50)
        val pageReq = json.parseToJsonElement(captureBodies(engine).last()).jsonObject
        assertEquals(
            """{"args":{"request":{"address":{"kind":"session","sessionId":"s-1"},"throughSeq":42,"beforeSeq":7,"maxMessages":50}}}""",
            pageReq["payload"].toString(),
        )
    }

    @Test
    fun `v011 keeps legacy payload shape`() = runTest {
        val engine = MockEngine { respond(ok("""{"entries":[],"hasMore":false}"""), HttpStatusCode.OK, headers()) }
        source(engine, DshWireProtocol.V011).fetchPage("s-1", beforeSeq = 7, maxMessages = 50)
        val body = json.parseToJsonElement(captureBodies(engine).single()).jsonObject
        assertEquals("session.history", body["method"]!!.jsonPrimitive.content)
        assertNull(body["payload"]!!.jsonObject["address"])
    }

    private fun captureBodies(engine: MockEngine): List<String> =
        engine.requestHistory.map { (it.body as io.ktor.http.content.TextContent).text }
}
