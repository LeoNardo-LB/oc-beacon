package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
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
 * V2ApiClient 端点测试——验证 V2 API 的 URL 路径、请求方法、响应解析。
 *
 * 使用 MockEngine 模拟 V2 服务器，真实执行 HTTP 请求/响应周期（L3 真实度）。
 * 每个测试验证：
 * 1. 请求路径正确（/api 前缀）
 * 2. HTTP 方法正确
 * 3. 响应正确解析为域模型
 */
class V2ApiClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val v2Conn = ServerConnection(
        baseUrl = "http://test-v2.local",
        authHeader = "Basic dGVzdDp0ZXN0",
        apiVersion = ApiVersion.V2
    )

    private fun buildClient(engine: MockEngine): V2ApiClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return V2ApiClient(ApiClient(httpClient, json))
    }

    // ============ Health ============

    @Test
    fun `getHealth parses V2 health response with version`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/health", request.url.encodedPath)
            respond(
                """{"healthy":true,"version":"2.0.1","pid":{"id":12345}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }
        val api = buildClient(engine)
        val health = api.getHealth(v2Conn)
        assertTrue(health.healthy)
        assertEquals("2.0.1", health.version)
    }

    // ============ Session ============

    @Test
    fun `listSessions requests correct V2 path and unwraps data array`() = runTest {
        val responseBody = """{"data":[{"id":"sess_1","projectID":"prj_1","time":{"created":1000,"updated":2000},"location":{"directory":"/home"}},{"id":"sess_2","projectID":"prj_1","time":{"created":3000,"updated":4000},"location":{"directory":"/home"}}],"cursor":{"previous":null,"next":null}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val sessions = api.listSessions(v2Conn)
        assertEquals(2, sessions.size)
        assertEquals("sess_1", sessions[0].id)
        assertEquals("prj_1", sessions[0].projectId)
        assertEquals(1000L, sessions[0].time.created)
    }

    @Test
    fun `getSession unwraps data wrapper`() = runTest {
        val responseBody = """{"data":{"id":"sess_1","projectID":"prj_1","time":{"created":1000,"updated":2000},"location":{"directory":"/home"}}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val session = api.getSession(v2Conn, "sess_1")
        assertEquals("sess_1", session.id)
        assertEquals("/home", session.directory)
    }

    @Test
    fun `createSession posts to V2 session endpoint`() = runTest {
        val responseBody = """{"data":{"id":"new_sess","projectID":"prj_1","time":{"created":1000,"updated":1000},"location":{"directory":"/proj"}}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val session = api.createSession(v2Conn, title = "Test", directory = "/proj")
        assertEquals("new_sess", session.id)
    }

    @Test
    fun `deleteSession sends DELETE to V2 path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1", request.url.encodedPath)
            assertEquals("DELETE", request.method.value)
            respond("", HttpStatusCode.OK)
        }
        val api = buildClient(engine)
        assertTrue(api.deleteSession(v2Conn, "sess_1"))
    }

    @Test
    fun `interruptSession sends POST to V2 interrupt path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1/interrupt", request.url.encodedPath)
            respond("", HttpStatusCode.NoContent)
        }
        val api = buildClient(engine)
        assertTrue(api.interruptSession(v2Conn, "sess_1"))
    }

    @Test
    fun `renameSession posts title to V2 rename path`() = runTest {
        val responseBody = """{"data":{"id":"sess_1","projectID":"prj_1","time":{"created":1000,"updated":2000},"location":{"directory":"/home"}}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1/rename", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val result = api.renameSession(v2Conn, "sess_1", "New Title")
        assertEquals("sess_1", result.id)
    }

    // ============ Message ============

    @Test
    fun `listMessages unwraps V2 data and maps messages`() = runTest {
        val responseBody = """{"data":[{"type":"user","id":"msg_1","time":{"created":1000},"text":"Hello"},{"type":"assistant","id":"msg_2","time":{"created":2000},"agent":"build","model":{"id":"gpt-4","providerID":"openai"},"content":[{"type":"text","text":"Hi there!"}]}],"cursor":{"previous":null,"next":null}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1/message", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val page = api.listMessages(v2Conn, "sess_1")
        assertEquals(2, page.messages.size)
        assertEquals("Hello", page.messages[0].parts.firstOrNull()?.let { (it as? dev.leonardo.ocbeacon.domain.model.Part.Text)?.text } ?: "")
    }

    @Test
    fun `prompt posts text to V2 prompt endpoint`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1/prompt", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            respond("""{"data":{"id":"pending_1","sessionID":"sess_1","timeCreated":1000,"type":"user","data":{"text":"test"},"delivery":"steer"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        assertTrue(api.prompt(v2Conn, "sess_1", "test message"))
    }

    @Test
    fun `switchModel posts to V2 model endpoint`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1/model", request.url.encodedPath)
            respond("", HttpStatusCode.OK)
        }
        val api = buildClient(engine)
        assertTrue(api.switchModel(v2Conn, "sess_1", "openai", "gpt-4"))
    }

    @Test
    fun `deleteMessage sends DELETE on V2 path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/sess_1/message/msg_1", request.url.encodedPath)
            respond("", HttpStatusCode.OK)
        }
        val api = buildClient(engine)
        assertTrue(api.deleteMessage(v2Conn, "sess_1", "msg_1"))
    }

    // ============ System / Agents ============

    @Test
    fun `listAgents unwraps V2 agent data`() = runTest {
        val responseBody = """{"data":[{"id":"build","name":"Build","mode":"primary","hidden":false},{"id":"plan","name":"Plan","mode":"primary","hidden":false}]}"""
        val engine = MockEngine { request ->
            assertEquals("/api/agent", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val agents = api.listAgents(v2Conn)
        assertEquals(2, agents.size)
        assertEquals("Build", agents[0].name)
        assertEquals("primary", agents[0].mode)
    }

    @Test
    fun `listCommands unwraps V2 command data`() = runTest {
        val responseBody = """{"data":[{"id":"init","name":"init","description":"Init project"}]}"""
        val engine = MockEngine { request ->
            assertEquals("/api/command", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val commands = api.listCommands(v2Conn)
        assertEquals(1, commands.size)
        assertEquals("init", commands[0].name)
    }

    @Test
    fun `listSkills unwraps V2 skill data`() = runTest {
        val responseBody = """{"data":[{"id":"skill_1","name":"Skill One","description":"A skill"}]}"""
        val engine = MockEngine { request ->
            assertEquals("/api/skill", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val skills = api.listSkills(v2Conn)
        assertEquals(1, skills.size)
        assertEquals("Skill One", skills[0].name)
    }

    // ============ MCP ============

    @Test
    fun `connectMcpServer posts to V2 mcp connect path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/mcp/test-server/connect", request.url.encodedPath)
            respond("true", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        assertTrue(api.connectMcpServer(v2Conn, "test-server"))
    }

    @Test
    fun `disconnectMcpServer posts to V2 mcp disconnect path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/mcp/test-server/disconnect", request.url.encodedPath)
            respond("true", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        assertTrue(api.disconnectMcpServer(v2Conn, "test-server"))
    }

    // ============ Provider ============

    @Test
    fun `getProviders fetches both providers and models from V2`() = runTest {
        val providerResponse = """{"data":[{"id":"openai","name":"OpenAI"},{"id":"anthropic","name":"Anthropic"}]}"""
        val modelResponse = """{"data":[{"id":"gpt-4","providerID":"openai","name":"GPT-4","limit":{"context":128000}},{"id":"claude-3","providerID":"anthropic","name":"Claude 3"}]}"""

        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            when {
                request.url.encodedPath == "/api/provider" -> {
                    respond(providerResponse, HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType to listOf("application/json")))
                }
                request.url.encodedPath == "/api/model" -> {
                    respond(modelResponse, HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType to listOf("application/json")))
                }
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }
        val api = buildClient(engine)
        val result = api.getProviders(v2Conn)
        assertEquals(2, result.providers.size)
        assertEquals("OpenAI", result.providers[0].name)
        // 验证模型被正确关联到 provider
        assertTrue(result.providers.any { p -> p.models.containsKey("gpt-4") })
        assertTrue(result.providers.any { p -> p.models.containsKey("claude-3") })
        assertEquals(2, callCount) // 确保两个端点都调用了
    }

    // ============ Permission / Question ============

    @Test
    fun `replyToPermission posts to V2 permission reply path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/permission/req_1/reply", request.url.encodedPath)
            respond("", HttpStatusCode.OK)
        }
        val api = buildClient(engine)
        assertTrue(api.replyToPermission(v2Conn, "req_1", "once"))
    }

    @Test
    fun `replyToQuestion posts answers to V2 question reply path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/question/req_1/reply", request.url.encodedPath)
            respond("", HttpStatusCode.OK)
        }
        val api = buildClient(engine)
        assertTrue(api.replyToQuestion(v2Conn, "req_1", listOf(listOf("option1"))))
    }

    @Test
    fun `rejectQuestion posts to V2 question reject path`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/question/req_1/reject", request.url.encodedPath)
            respond("", HttpStatusCode.OK)
        }
        val api = buildClient(engine)
        assertTrue(api.rejectQuestion(v2Conn, "req_1"))
    }

    // ============ Error handling ============

    @Test
    fun `listSessions handles empty data array`() = runTest {
        val responseBody = """{"data":[],"cursor":{"previous":null,"next":null}}"""
        val engine = MockEngine { _ ->
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val sessions = api.listSessions(v2Conn)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `fetchSessionStatus returns active sessions as busy`() = runTest {
        // 真实服务器契约（2026-08-11 实测）：data 是对象 {sessionID: {type: "running"}}
        val responseBody = """{"data":{"sess_1":{"type":"running"},"sess_2":{"type":"idle"}}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session/active", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val result = api.fetchSessionStatus(v2Conn)
        assertTrue(result.isSuccess)
        val statusMap = result.getOrThrow()
        assertEquals(2, statusMap.size)
        // running → busy（RestSessionStatusInfo 的 when(type) 消费）
        assertEquals("busy", statusMap["sess_1"]?.type)
        assertEquals("idle", statusMap["sess_2"]?.type)
    }

    @Test
    fun `fetchSessionStatus handles empty active object`() = runTest {
        val responseBody = """{"data":{}}"""
        val engine = MockEngine { request ->
            assertEquals("/api/session/active", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val result = api.fetchSessionStatus(v2Conn)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getConfig parses bare array with info subobject`() = runTest {
        // 真实服务器契约：裸数组 [{type:"document", path, info:{配置}}]
        val responseBody = """[{"type":"document","path":"/home/.config/opencode/opencode.jsonc",
            "info":{"$schema":"https://opencode.ai/config.json","default_agent":"build",
                    "disabled_providers":["provider-x"],"model":"glm-5.2"}}]"""
        val engine = MockEngine { request ->
            assertEquals("/api/config", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val result = api.getConfig(v2Conn)
        assertEquals("build", result.defaultAgent)
        assertEquals(listOf("provider-x"), result.disabledProviders)
        assertEquals("glm-5.2", result.model)
    }

    @Test
    fun `getMcpStatus parses data array with nested status objects`() = runTest {
        // 真实服务器契约：{"location":..., "data":[{name, status:{status}}]}
        val responseBody = """{"location":{"directory":"/home"},"data":[
            {"name":"agentmemory","status":{"status":"connected"}},
            {"name":"context7","status":{"status":"connected"}},
            {"name":"failed-mcp","status":{"status":"failed","error":"connection refused"}}
        ]}"""
        val engine = MockEngine { request ->
            assertEquals("/api/mcp", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = buildClient(engine)
        val result = api.getMcpStatus(v2Conn)
        assertEquals(3, result.size)
        assertEquals("connected", result["agentmemory"]?.status)
        assertEquals("connected", result["context7"]?.status)
        assertEquals("failed", result["failed-mcp"]?.status)
        assertEquals("connection refused", result["failed-mcp"]?.error)
    }
}
