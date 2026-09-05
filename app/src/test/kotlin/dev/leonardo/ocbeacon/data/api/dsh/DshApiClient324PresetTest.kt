package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324② preset 管理 wire 面测试：agentPresets/list 扩展解析（trust/broken/
 * authorable）+ agentPresets/read|copy|deletePreset。
 *
 * 契约锚点：dsh-agent-presets typert（roster {presets[],authorable}、read
 * {agentPreset,trust,content,name?,description?}、copy(from,id,name?)、
 * deletePreset(id)；copy/delete 为 z.void() 回程）。
 */
class DshApiClient324PresetTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val conn = ServerConnection(
        baseUrl = "http://dsh-test.local",
        authHeader = null,
        apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V1,
        serverType = ServerType.Dsh,
    )

    private fun client(engine: MockEngine, protocol: DshWireProtocol = DshWireProtocol.V012): DshApiClient {
        val registry = io.mockk.mockk<DshConnectionRegistry>(relaxed = true)
        io.mockk.every { registry.protocolOf(any()) } returns protocol
        io.mockk.every { registry.cookieHeader(any()) } returns null
        return DshApiClient(
            DshRpcClient(dev.leonardo.ocbeacon.data.api.ApiClient(HttpClient(engine), json), registry),
            FixedSource(protocol),
        )
    }

    private class FixedSource(private val protocol: DshWireProtocol?) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
    }

    private fun ok(value: String) =
        """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":$value}}""".trimIndent()

    private fun okVoid() =
        """{"type":"server-response","rpcId":"r","result":{"ok":true}}""".trimIndent()

    private fun headers() = io.ktor.http.headersOf("Content-Type" to listOf("application/json"))

    private fun bodyOf(request: HttpRequestData): String = (request.body as io.ktor.http.content.TextContent).text

    @Test
    fun `roster parses trust broken and authorable`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"presets":[
                    {"id":"code","trust":"system","isDefault":true,"name":"Code"},
                    {"id":"mine","trust":"user","isDefault":false,"name":"Mine","broken":"preset file unreadable"}
                ],"authorable":true}"""),
                HttpStatusCode.OK, headers(),
            )
        }
        val roster = client(engine).agentPresetRoster(conn)
        assertTrue(roster.authorable)
        assertEquals(2, roster.presets.size)
        assertEquals("system", roster.presets[0].trust)
        assertNull(roster.presets[0].broken)
        assertEquals("user", roster.presets[1].trust)
        assertEquals("preset file unreadable", roster.presets[1].broken)
        // 既有 listAgentPresets 委托同源解析
        assertEquals(2, client(engine).listAgentPresets(conn).size)
    }

    @Test
    fun `readAgentPreset flat-sends agentPreset and maps document`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/agentPresets/read", req.url.encodedPath)
            val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("code", args["agentPreset"]!!.jsonPrimitive.content)
            respond(
                ok("""{"agentPreset":"code","trust":"system","content":"# composition","name":"Code","description":"d"}"""),
                HttpStatusCode.OK, headers(),
            )
        }
        val doc = client(engine).readAgentPreset(conn, "code")!!
        assertEquals("code", doc.agentPreset)
        assertEquals("system", doc.trust)
        assertEquals("# composition", doc.content)
        assertEquals("Code", doc.name)
        assertEquals("d", doc.description)
    }

    @Test
    fun `readAgentPreset null on absent preset`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"agent-preset-not-found","message":"nope"}}}""",
                HttpStatusCode.OK, headers(),
            )
        }
        assertNull(client(engine).readAgentPreset(conn, "ghost"))
    }

    @Test
    fun `copyAgentPreset sends from id name and tolerates void result`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/agentPresets/copy", req.url.encodedPath)
            val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("code", args["from"]!!.jsonPrimitive.content)
            assertEquals("code-copy", args["id"]!!.jsonPrimitive.content)
            assertEquals("My Copy", args["name"]!!.jsonPrimitive.content)
            respond(okVoid(), HttpStatusCode.OK, headers())
        }
        assertTrue(client(engine).copyAgentPreset(conn, from = "code", id = "code-copy", name = "My Copy"))
    }

    @Test
    fun `copyAgentPreset omits blank name`() = runTest {
        val engine = MockEngine { req ->
            val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
            assertNull(args["name"])
            respond(okVoid(), HttpStatusCode.OK, headers())
        }
        assertTrue(client(engine).copyAgentPreset(conn, from = "code", id = "code-copy", name = null))
    }

    @Test
    fun `deleteAgentPreset sends id and tolerates void result`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/agentPresets/deletePreset", req.url.encodedPath)
            val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("code-copy", args["id"]!!.jsonPrimitive.content)
            respond(okVoid(), HttpStatusCode.OK, headers())
        }
        assertTrue(client(engine).deleteAgentPreset(conn, "code-copy"))
    }

    @Test
    fun `deleteAgentPreset false on server refusal`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"agent-preset-not-found","message":"nope"}}}""",
                HttpStatusCode.OK, headers(),
            )
        }
        assertFalse(client(engine).deleteAgentPreset(conn, "ghost"))
    }
}
