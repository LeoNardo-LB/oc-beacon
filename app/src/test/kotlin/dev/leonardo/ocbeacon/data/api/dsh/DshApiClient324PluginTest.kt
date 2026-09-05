package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324③ 插件配置与清单 wire 面测试：settings/describe 全量快照、settings/mutate
 * 泛化 ops、pluginInventory/list 解析。
 *
 * 契约锚点：dsh-api-settings-controller / dsh-host-plugin-inventory typert
 * （describe {writable,hasDocument,namespaces[]}；mutate 回 namespace 快照；
 * pluginInventory {entries[],agentPresets?[].rows[]}——enabled 三态
 * false|true|"conditional"，fiberPhase null|pending|active|failed|loading|unloading）。
 */
class DshApiClient324PluginTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val conn = ServerConnection(
        baseUrl = "http://dsh-test.local",
        authHeader = null,
        apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V1,
        serverType = ServerType.Dsh,
    )

    private fun client(engine: MockEngine): DshApiClient {
        val registry = io.mockk.mockk<DshConnectionRegistry>(relaxed = true)
        io.mockk.every { registry.protocolOf(any()) } returns DshWireProtocol.V012
        io.mockk.every { registry.cookieHeader(any()) } returns null
        return DshApiClient(
            DshRpcClient(dev.leonardo.ocbeacon.data.api.ApiClient(HttpClient(engine), json), registry),
            FixedSource(DshWireProtocol.V012),
        )
    }

    private class FixedSource(private val protocol: DshWireProtocol?) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
    }

    private fun ok(value: String) =
        """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":$value}}""".trimIndent()

    private fun headers() = io.ktor.http.headersOf("Content-Type" to listOf("application/json"))

    private fun bodyOf(req: io.ktor.client.request.HttpRequestData): String =
        (req.body as io.ktor.http.content.TextContent).text

    @Test
    fun `describeSettings maps writable and namespace entries raw json`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"writable":true,"hasDocument":false,"namespaces":[
                    {"ns":"shell","schema":{},"value":{"timeoutMs":120000},"applies":"live","secrets":[],"revision":3},
                    {"ns":"agent-loop","schema":{},"value":{"maxParallelToolCalls":10},"applies":"live","secrets":[],"revision":5}
                ]}"""),
                HttpStatusCode.OK, headers(),
            )
        }
        val snapshot = client(engine).describeSettings(conn)!!
        assertTrue(snapshot.writable)
        assertEquals(2, snapshot.namespaces.size)
        assertEquals("shell", snapshot.namespaces[0].dshStr("ns"))
        assertEquals(3L, snapshot.namespaces[0].dshLong("revision"))
    }

    @Test
    fun `mutateSettings sends ns ops revision flat`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/settings/mutate", req.url.encodedPath)
            val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("shell", args["ns"]!!.jsonPrimitive.content)
            assertEquals(3.0, args["expectedRevision"]!!.jsonPrimitive.content.toDouble(), 0.0)
            val ops = args["ops"]!!.jsonArray
            assertEquals(2, ops.size)
            assertEquals("set", ops[0].jsonObject["op"]!!.jsonPrimitive.content)
            assertEquals("""["timeoutMs"]""", ops[0].jsonObject["path"]!!.jsonArray.toString())
            assertEquals(60000.0, ops[0].jsonObject["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
            assertEquals("unset", ops[1].jsonObject["op"]!!.jsonPrimitive.content)
            respond(ok("""{"ns":"shell","revision":4}"""), HttpStatusCode.OK, headers())
        }
        val okResult = client(engine).mutateSettings(
            conn,
            ns = "shell",
            ops = listOf(
                dev.leonardo.ocbeacon.domain.model.DshSettingsOp.Set("timeoutMs", kotlinx.serialization.json.JsonPrimitive(60000)),
                dev.leonardo.ocbeacon.domain.model.DshSettingsOp.Unset("legacyFlag"),
            ),
            expectedRevision = 3,
        )
        assertTrue(okResult)
    }

    @Test
    fun `listPluginInventory parses entries and preset rows with conditional state`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{
                    "entries":[
                        {"entryId":"e1","moduleName":"dsh-shell","enabled":true,"fiberPhase":"active"},
                        {"entryId":"e2","moduleName":"dsh-goal","enabled":false,"fiberPhase":null}
                    ],
                    "agentPresets":[{
                        "id":"code","trust":"system","name":"Code","isDefault":true,
                        "rows":[
                            {"entryId":"e1","moduleName":"dsh-shell","enabled":true,"fiberPhase":"active"},
                            {"entryId":null,"moduleName":"dsh-cordis","enabled":"conditional","condition":"preset declares cordis","fiberPhase":null}
                        ]
                    }]
                }"""),
                HttpStatusCode.OK, headers(),
            )
        }
        val inventory = client(engine).listPluginInventory(conn)!!
        assertEquals(2, inventory.entries.size)
        assertEquals("dsh-shell", inventory.entries[0].moduleName)
        assertTrue(inventory.entries[0].enabled)
        assertEquals("active", inventory.entries[0].fiberPhase)
        assertEquals(1, inventory.presets.size)
        val preset = inventory.presets[0]
        assertEquals("code", preset.id)
        assertEquals(2, preset.rows.size)
        assertEquals(dev.leonardo.ocbeacon.domain.model.DshPluginEnabled.ENABLED, preset.rows[0].enabled)
        assertEquals(dev.leonardo.ocbeacon.domain.model.DshPluginEnabled.CONDITIONAL, preset.rows[1].enabled)
        assertEquals("preset declares cordis", preset.rows[1].condition)
    }

    @Test
    fun `listPluginInventory null on transport failure`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        assertEquals(null, client(engine).listPluginInventory(conn))
    }
}
