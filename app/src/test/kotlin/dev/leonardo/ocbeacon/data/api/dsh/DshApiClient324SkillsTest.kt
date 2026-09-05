package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324④ skills 触发组 wire 面测试：skills/list（会话维度）。
 *
 * 契约锚点：dsh-api-session-controller typert——request {sessionId}（单 request
 * 参 WRAPPED）→ {skills:[{name,description,whenToUse?,modelInvocable}]}。
 */
class DshApiClient324SkillsTest {

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

    private fun headers() = io.ktor.http.headersOf("Content-Type" to listOf("application/json"))

    @Test
    fun `listSessionSkills wraps sessionId in request and maps skills`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/api/skills/list", req.url.encodedPath)
            val body = json.parseToJsonElement(
                (req.body as io.ktor.http.content.TextContent).text
            ).jsonObject
            assertEquals("skills/list", body["method"]!!.jsonPrimitive.content)
            assertEquals(
                """{"args":{"request":{"sessionId":"s-1"}}}""",
                body["payload"].toString(),
            )
            respond(
                ok("""{"skills":[
                    {"name":"calculator","description":"Numeric toolkit","modelInvocable":true},
                    {"name":"lazy","description":"Lazy helper","whenToUse":"only on request","modelInvocable":false}
                ]}"""),
                HttpStatusCode.OK, headers(),
            )
        }
        val skills = client(engine).listSessionSkills(conn, "s-1")
        assertEquals(2, skills.size)
        assertEquals("calculator", skills[0].name)
        assertEquals("Numeric toolkit", skills[0].description)
        assertEquals(null, skills[0].whenToUse)
        assertTrue(skills[0].modelInvocable)
        assertEquals("only on request", skills[1].whenToUse)
        assertEquals(false, skills[1].modelInvocable)
    }

    @Test
    fun `listSessionSkills degrades to empty on failure`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        assertTrue(client(engine).listSessionSkills(conn, "s-1").isEmpty())
    }
}
