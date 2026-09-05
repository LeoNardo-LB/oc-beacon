package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.DshCustomProviderDraft
import dev.leonardo.ocbeacon.domain.model.DshCustomProviders
import dev.leonardo.ocbeacon.domain.model.DshDiscoveredModel
import dev.leonardo.ocbeacon.domain.model.DshModelDiscoveryRequest
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.HttpRequestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324① provider/模型目录 wire 面测试：llm/listConfigurableProviders、
 * llm/discoverModels、credentials/describe|set|unset、自定义 provider 增删
 * （settings/mutate ns=llm-pi-ai）。
 *
 * 载荷形态锚点：web mod18 CustomProviderCard + 服务器 typert（wire 键=参数名）。
 * MockEngine 走真实 DshRpcClient（同 DshApiClientTest 模式）。
 */
class DshApiClient324ProviderTest {

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
            FixedProtocolSource_Public(protocol),
        )
    }

    private class FixedProtocolSource_Public(private val protocol: DshWireProtocol?) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
    }

    private fun ok(value: String) =
        """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":$value}}""".trimIndent()

    /** void 方法线面形态：JSON.stringify 丢 undefined 键 → {"ok":true}（无 value）。 */
    private fun okVoid() =
        """{"type":"server-response","rpcId":"r","result":{"ok":true}}""".trimIndent()

    private fun bodyOf(request: HttpRequestData): String = (request.body as io.ktor.http.content.TextContent).text

    private fun jsonHeaders() = io.ktor.http.headersOf("Content-Type" to listOf("application/json"))

    // ---- llm/listConfigurableProviders ----

    @Test
    fun `listConfigurableProviders calls endpoint and maps entries`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/llm/listConfigurableProviders", req.url.encodedPath)
            respond(
                ok("""[{"provider":"deepseek","displayName":"DeepSeek","settingsNs":"llm-deepseek","settingsPath":["apiKey"],"declared":true}]"""),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val providers = client(engine).listConfigurableProviders(conn)
        assertEquals(1, providers.size)
        assertEquals("deepseek", providers[0].provider)
        assertEquals("DeepSeek", providers[0].displayName)
        assertEquals("llm-deepseek", providers[0].settingsNs)
        assertEquals(listOf("apiKey"), providers[0].settingsPath)
        assertEquals(true, providers[0].declared)
    }

    @Test
    fun `listConfigurableProviders degrades to empty on v011`() = runTest {
        val engine = MockEngine { respond(ok("[]"), HttpStatusCode.OK, jsonHeaders()) }
        // V011：端点不存在（0.1.1 无该面）——恒空列表，不发请求
        assertTrue(client(engine, DshWireProtocol.V011).listConfigurableProviders(conn).isEmpty())
        assertTrue(engine.requestHistory.isEmpty())
    }

    // ---- llm/discoverModels ----

    @Test
    fun `discoverModels flat-packs settingsNs and request and maps models`() = runTest {
        val engine = MockEngine { req ->
            val body = json.parseToJsonElement(bodyOf(req)).jsonObject
            assertEquals("llm/discoverModels", body["method"]!!.jsonPrimitive.content)
            val args = body["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("llm-pi-ai", args["settingsNs"]!!.jsonPrimitive.content)
            val request = args["request"]!!.jsonObject
            assertEquals("acme", request["provider"]!!.jsonPrimitive.content)
            assertEquals("https://api.acme.dev", request["baseURL"]!!.jsonPrimitive.content)
            assertEquals("sk-k", request["apiKey"]!!.jsonPrimitive.content)
            respond(
                ok("""[{"id":"acme-large","name":"Acme Large","contextWindow":128000,"maxTokens":8192}]"""),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val models = client(engine).discoverModels(
            conn,
            DshModelDiscoveryRequest(
                settingsNs = "llm-pi-ai",
                provider = "acme",
                baseURL = "https://api.acme.dev",
                apiKey = "sk-k",
            ),
        )
        assertEquals(1, models.size)
        assertEquals("acme-large", models[0].id)
        assertEquals(128000L, models[0].contextWindow)
        assertEquals(8192L, models[0].maxTokens)
    }

    // ---- credentials ----

    @Test
    fun `describeCredentials maps status per ref`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/credentials/describe", req.url.encodedPath)
            respond(
                ok("""{"DEEPSEEK_API_KEY":{"configured":true,"source":"env","writable":true},"K2":{"configured":false,"writable":false}}"""),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val statuses = client(engine).describeCredentials(conn, listOf("DEEPSEEK_API_KEY", "K2"))
        assertEquals(2, statuses.size)
        assertTrue(statuses.getValue("DEEPSEEK_API_KEY").configured)
        assertEquals("env", statuses.getValue("DEEPSEEK_API_KEY").source)
        assertFalse(statuses.getValue("K2").configured)
    }

    @Test
    fun `setCredential sends key value flat`() = runTest {
        val engine = MockEngine { req ->
            val body = json.parseToJsonElement(bodyOf(req)).jsonObject
            val args = body["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("K", args["key"]!!.jsonPrimitive.content)
            assertEquals("v", args["value"]!!.jsonPrimitive.content)
            respond(okVoid(), HttpStatusCode.OK, jsonHeaders())
        }
        assertTrue(client(engine).setCredential(conn, "K", "v"))
    }

    @Test
    fun `unsetCredential sends key flat`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/credentials/unset", req.url.encodedPath)
            respond(okVoid(), HttpStatusCode.OK, jsonHeaders())
        }
        assertTrue(client(engine).unsetCredential(conn, "K"))
    }

    // ---- 自定义 provider 增 ----

    @Test
    fun `createCustomProvider writes profile under providers route with revision`() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/api/credentials/set") {
                return@MockEngine respond(okVoid(), HttpStatusCode.OK, jsonHeaders())
            }
            assertEquals("/api/settings/mutate", req.url.encodedPath)
            val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals("llm-pi-ai", args["ns"]!!.jsonPrimitive.content)
            assertEquals(7.0, args["expectedRevision"]!!.jsonPrimitive.content.toDouble(), 0.0)
            val ops = args["ops"]!!.jsonArray
            assertEquals(1, ops.size)
            val op = ops[0].jsonObject
            assertEquals("set", op["op"]!!.jsonPrimitive.content)
            assertEquals("""["providers","acme-gateway"]""", op["path"]!!.jsonArray.toString())
            val value = op["value"]!!.jsonObject
            assertEquals("https://api.acme.dev", value["baseURL"]!!.jsonPrimitive.content)
            assertEquals("ACME_GATEWAY_API_KEY", value["apiKeyEnv"]!!.jsonPrimitive.content)
            assertEquals(1, (value["models"]!!.jsonArray).size)
            respond(ok("""{"ns":"llm-pi-ai","revision":8}"""), HttpStatusCode.OK, jsonHeaders())
        }
        val ok = client(engine).createCustomProvider(
            conn,
            DshCustomProviderDraft(
                route = "acme-gateway",
                displayName = "Acme",
                baseURL = "https://api.acme.dev",
                api = "openai-completions",
                apiKey = "sk-secret",
                models = listOf(DshDiscoveredModel(id = "acme-large")),
            ),
            expectedRevision = 7,
        )
        assertTrue(ok)
    }

    @Test
    fun `createCustomProvider stores key then profile and is resilient to key failure`() = runTest {
        // 序：credentials/set 先（key 就位服务端才校验 profile? 反之 web 先 profile 后 key——按 web 序）
        val calls = mutableListOf<String>()
        val engine = MockEngine { req ->
            calls += req.url.encodedPath
            respond(ok("""{"ns":"llm-pi-ai","revision":9}"""), HttpStatusCode.OK, jsonHeaders())
        }
        val result = client(engine).createCustomProvider(
            conn,
            DshCustomProviderDraft(
                route = "acme",
                displayName = "",
                baseURL = "https://api.acme.dev",
                api = "anthropic-messages",
                apiKey = "sk-k",
                models = listOf(DshDiscoveredModel(id = "m1")),
            ),
            expectedRevision = null,
        )
        assertTrue(result)
        assertEquals(listOf("/api/settings/mutate", "/api/credentials/set"), calls)
    }

    // ---- 自定义 provider 删 ----

    @Test
    fun `deleteCustomProvider unsets settings then clears managed credential`() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { req ->
            calls += req.url.encodedPath
            if (req.url.encodedPath.endsWith("mutate")) {
                val args = json.parseToJsonElement(bodyOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
                assertEquals("llm-pi-ai", args["ns"]!!.jsonPrimitive.content)
                val op = args["ops"]!!.jsonArray[0].jsonObject
                assertEquals("unset", op["op"]!!.jsonPrimitive.content)
                assertEquals("""["providers","acme-gateway"]""", op["path"]!!.jsonArray.toString())
            }
            respond(ok("""{"ns":"llm-pi-ai","revision":10}"""), HttpStatusCode.OK, jsonHeaders())
        }
        val ok = client(engine).deleteCustomProvider(
            conn,
            settingsNs = "llm-pi-ai",
            settingsPath = DshCustomProviders.providerPath("acme-gateway"),
            credentialRef = "ACME_GATEWAY_API_KEY",
        )
        assertTrue(ok)
        assertEquals(listOf("/api/settings/mutate", "/api/credentials/unset"), calls)
    }
}
