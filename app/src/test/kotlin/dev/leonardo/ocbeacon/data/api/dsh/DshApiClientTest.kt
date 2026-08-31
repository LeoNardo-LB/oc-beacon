package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.dto.request.ServerConfigPatch
import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshApiClient 七域实现测试（backlog #276 步骤③；设计 §2.6 方法面映射）。
 *
 * MockEngine 走真实 DshRpcClient（信封/URL/body 断言在 #274 已覆盖，此处只断
 * 方法名/payload 形态/域模型映射/降级语义）。
 */
class DshApiClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val conn = ServerConnection(
        baseUrl = "http://dsh-test.local",
        authHeader = null,
        apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V1,
        serverType = ServerType.Dsh,
    )

    private fun client(engine: MockEngine): DshApiClient =
        DshApiClient(DshRpcClient(ApiClient(HttpClient(engine), json)))

    private fun ok(value: String) =
        """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":$value}}""".trimIndent()

    private val sessionListValue = """{"items":[
        {"sessionId":"s-1","updatedAt":1788109000023,"running":false,"blank":false,
         "cwd":"/w/one","projections":{"asOfSeq":5,"values":{"title":{"title":"T1"}}}},
        {"sessionId":"s-2","updatedAt":2,"running":true,"blank":false,"cwd":"/w/two"},
        {"sessionId":"s-3","updatedAt":3,"running":false,"blank":true,"cwd":"/w/one"}
    ]}""".trimIndent()

    private fun captureRequests(engine: MockEngine) = engine.requestHistory

    private fun bodyTextOf(request: io.ktor.client.request.HttpRequestData): String =
        (request.body as TextContent).text

    // ============ SessionApi ============

    @Test
    fun `listSessions calls session list and maps items`() = runTest {
        val engine = MockEngine { respond(ok(sessionListValue), HttpStatusCode.OK, jsonHeaders()) }
        val sessions = client(engine).listSessions(conn)
        assertEquals(listOf("s-1", "s-2"), sessions.map { it.id }) // blank 会话滤除
        assertEquals("T1", sessions[0].title)
        assertEquals("/w/one", sessions[0].directory)
        assertEquals(1788109000023L, sessions[0].time.updated)
        // 请求形态：POST /api/session.list，payload 空对象（cursor 忽略——P-4 未实现）
        val req = captureRequests(engine).single()
        assertEquals("/api/session.list", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session.list", body["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listSessions directory filter applies locally by cwd`() = runTest {
        val engine = MockEngine { respond(ok(sessionListValue), HttpStatusCode.OK, jsonHeaders()) }
        val sessions = client(engine).listSessions(conn, directory = "/w/two")
        assertEquals(listOf("s-2"), sessions.map { it.id })
    }

    @Test
    fun `interruptSession calls session cancel`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).interruptSession(conn, "s-9"))
        val req = captureRequests(engine).single()
        assertEquals("/api/session.cancel", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("s-9", body["payload"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deleteSession throws UnsupportedServerCapability`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        val ex = runCatching { client(engine).deleteSession(conn, "s-1") }.exceptionOrNull()
        assertTrue(ex is UnsupportedServerCapability)
        assertTrue(engine.requestHistory.isEmpty()) // 不发请求
    }

    @Test
    fun `session degradations follow v1 constant precedent`() = runTest {
        val api = client(MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) })
        assertFalse(api.backgroundSession(conn, "s"))
        assertTrue(api.activeSessions(conn).isEmpty())
        assertTrue(api.getSessionTodos(conn, "s").isEmpty())
        assertTrue(api.listSessionStatus(conn).isEmpty())
        assertTrue(api.getSessionDiff(conn, "s").isEmpty())
        assertFalse(api.executeCommand(conn, "s", "cmd"))
        assertTrue(api.fetchSessionStatus(conn).isSuccess)
        assertTrue(api.fetchSessionStatus(conn).getOrDefault(emptyMap()).isEmpty())
    }

    @Test
    fun `fetchSessionStatus probes host describe for liveness`() = runTest {
        val engine = MockEngine { respond(ok("{\"version\":\"0.0.1\"}"), HttpStatusCode.OK, jsonHeaders()) }
        val result = client(engine).fetchSessionStatus(conn)
        assertTrue(result.isSuccess)
        assertEquals("/api/host.describe", captureRequests(engine).single().url.encodedPath)
    }

    @Test
    fun `renameSession calls session rename`() = runTest {
        val engine = MockEngine { respond(ok("{\"sessionId\":\"s-1\",\"updatedAt\":9}"), HttpStatusCode.OK, jsonHeaders()) }
        val session = client(engine).renameSession(conn, "s-1", "new title")
        assertEquals("s-1", session.id)
        assertEquals("new title", session.title)
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("session.rename", body["method"]!!.jsonPrimitive.content)
        assertEquals("new title", body["payload"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    // ============ MessageApi ============

    @Test
    fun `promptAsync posts session prompt with text content part`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        val admission = client(engine).promptAsync(
            conn, "s-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "hello")),
        )
        assertNull(admission) // DSH 无 V2 式受理回执——用户消息经 WS 回显（V1 先例 null）
        val req = captureRequests(engine).single()
        assertEquals("/api/session.prompt", req.url.encodedPath)
        val payload = json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        val content = payload["content"].toString()
        assertTrue(content.contains("\"type\":\"text\""))
        assertTrue(content.contains("hello"))
        // E2E 回归（2026-08-31）：mode 必填，缺席被服务端整单拒绝（zod expected queue|steer）
        assertEquals("queue", payload["mode"]!!.jsonPrimitive.content)
    }

    /**
     * #276 后端接口补全：compact 根治——/compact 走斜杠命令通道（§1.6：prompt
     * 单文本块以 / 开头 = 服务端命令注册表执行，不进模型），受理成功即 true
     * （压缩完成信号走 compaction/end → SessionCompacted 事件）。providerId/
     * modelId 对 DSH 无效（命令通道无模型参数）。
     */
    @Test
    fun `compactSession sends compact slash command via session prompt`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).compactSession(conn, "s-1", "ignored-provider", "ignored-model"))
        val req = captureRequests(engine).single()
        assertEquals("/api/session.prompt", req.url.encodedPath)
        val payload = json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("queue", payload["mode"]!!.jsonPrimitive.content)
        val content = payload["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/compact", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    /** 命令被服务端拒绝（如 unknown-command）→ 异常上抛（repository 层收编为失败）。 */
    @Test
    fun `compactSession propagates command rejection`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"unknown-command","message":"no such command"}}}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val outcome = runCatching { client(engine).compactSession(conn, "s-1", "p", "m") }
        assertTrue(outcome.isFailure)
        assertEquals("unknown-command", (outcome.exceptionOrNull() as DshApiError).code?.wire)
    }

    /**
     * #276 后端接口补全：导出根治——GET /api/session.export?sessionId=（非信封
     * 入口，直接 zip 流）逐块写出 + onProgress 累计字节；conn 无 auth 头。
     */
    @Test
    fun `exportSessionToStream streams zip bytes with progress`() = runTest {
        val zipBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4, 5, 0x50, 0x4b, 0x05, 0x06)
        val engine = MockEngine {
            respond(zipBytes, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/zip")))
        }
        val output = java.io.ByteArrayOutputStream()
        val progresses = mutableListOf<Long>()
        client(engine).exportSessionToStream(conn, "s-1", output) { progresses.add(it) }
        org.junit.Assert.assertArrayEquals(zipBytes, output.toByteArray())
        assertEquals(zipBytes.size.toLong(), progresses.last()) // 累计字节终值 = 全量
        assertTrue(progresses.zipWithNext().all { (a, b) -> b >= a }) // 单调不减
        val req = captureRequests(engine).single()
        assertEquals("/api/session.export", req.url.encodedPath)
        assertEquals("s-1", req.url.parameters["sessionId"])
        assertNull(req.headers["Authorization"]) // DSH 无鉴权——不带 auth 头
    }

    /** HTTP 非 200（搬运层错误）→ 抛 IOException（导出失败通知依赖异常路径）。 */
    @Test
    fun `exportSessionToStream fails on http error`() = runTest {
        val engine = MockEngine { respond("no session", HttpStatusCode.NotFound) }
        val outcome = runCatching {
            client(engine).exportSessionToStream(conn, "missing", java.io.ByteArrayOutputStream()) {}
        }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `listMessages folds history entries into message page`() = runTest {
        val historyValue = """{"entries":[
            {"event":{"type":"user/message","seq":5,"time":11,"data":{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}}},
            {"event":{"type":"assistant/message","seq":9,"time":19,"data":{"turn":1,"step":1,"usage":{"inputTokens":1,"outputTokens":2},"message":{"content":[{"type":"text","text":"answer"}]}}}},
            {"event":{"type":"assistant/chunk","seq":10,"time":20,"data":{"turn":1,"step":1,"chunk":{"type":"text-delta","index":1,"text":"x"}}}}
        ],"hasMore":true}""".trimIndent()
        val engine = MockEngine { respond(ok(historyValue), HttpStatusCode.OK, jsonHeaders()) }
        val page = client(engine).listMessages(conn, "s-1")
        assertEquals(2, page.messages.size)
        assertEquals("seq-5", page.messages[0].info.id)
        assertEquals("seq-9", page.messages[1].info.id)
        assertEquals(1, page.messages[1].parts.size) // chunk 不进历史 fold
        assertNotNull(page.nextCursor) // hasMore=true → 下一页游标（页内最小 seq）
        assertEquals("5", page.nextCursor)
    }

    @Test
    fun `replyToPermission responds with mapped outcome`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).replyToPermission(conn, "s", "rpc-1", "once"))
        val req = captureRequests(engine).single()
        assertEquals("/api/respond", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("client-response", body["type"]!!.jsonPrimitive.content)
        assertEquals("rpc-1", body["rpcId"]!!.jsonPrimitive.content)
        assertEquals("allowed-once", body["result"]!!.jsonObject["value"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    // ============ SystemApi / FileApi / TerminalApi / ShellApi / ProviderApi ============

    @Test
    fun `getHealth probes host describe`() = runTest {
        val engine = MockEngine { respond(ok("{\"version\":\"0.0.1\"}"), HttpStatusCode.OK, jsonHeaders()) }
        val health = client(engine).getHealth(conn)
        assertTrue(health.healthy)
        assertEquals("0.0.1", health.version)
    }

    @Test
    fun `listDirectory maps host listDirectory entries`() = runTest {
        val engine = MockEngine {
            respond(ok("{\"entries\":[{\"name\":\"src\",\"type\":\"directory\"},{\"name\":\"a.kt\",\"type\":\"file\"}]}"),
                HttpStatusCode.OK, jsonHeaders())
        }
        val nodes = client(engine).listDirectory(conn, path = "/w")
        assertEquals(2, nodes.size)
        assertEquals("src", nodes[0].name)
        assertEquals("directory", nodes[0].type)
        assertEquals("a.kt", nodes[1].name)
        assertEquals("file", nodes[1].type)
    }

    /**
     * #276 终验 V4（DSH 目录惰性探测）：活体样本（/tmp/dsh-openapi-cases/04）
     * 证实 host.listDirectory 条目仅 {name,path,hidden}——无 type 判别。
     * 缺省映射必须是 directory（全部可展开）；真实文件由 UI 层在展开失败
     * （directory-unreadable）时转叶。显式 type 字段若协议未来补齐仍尊重原值。
     */
    @Test
    fun `listDirectory maps typeless entries to directory for lazy probing`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"entries":[{"name":"src","path":"/w/src","hidden":false},{"name":"a.kt","path":"/w/a.kt","hidden":false}]}"""),
                HttpStatusCode.OK, jsonHeaders())
        }
        val nodes = client(engine).listDirectory(conn, path = "/w")
        assertEquals(listOf("directory", "directory"), nodes.map { it.type })
        // 条目 path 透传（活体样本自带 fully-qualified path）
        assertEquals("/w/src", nodes[0].path)
        assertEquals("/w/a.kt", nodes[1].path)
    }

    @Test
    fun `unsupported domains throw UnsupportedServerCapability`() = runTest {
        val api = client(MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) })
        assertTrue(runCatching { api.deleteSession(conn, "s") }.exceptionOrNull() is UnsupportedServerCapability)
        assertTrue(runCatching { api.readFile(conn, "/x") }.exceptionOrNull() is UnsupportedServerCapability)
        assertTrue(runCatching { api.createPty(conn) }.exceptionOrNull() is UnsupportedServerCapability)
        assertTrue(runCatching { api.listShells(conn) }.exceptionOrNull() is UnsupportedServerCapability)
        assertTrue(runCatching { api.updateConfig(conn, ServerConfigPatch()) }.exceptionOrNull() is UnsupportedServerCapability)
    }

    /**
     * #276 走查 N2（D1 workspace 空路径）：path="" 不得直传（DSH 要求
     * fully-qualified path，空串 → directory-unreadable）——从 workspace.list
     * 首个 workspace path 解析根（走查实证 UI 的「workspace /home/…」标签即
     * 该源），再以根路径请求 host.listDirectory。
     */
    @Test
    fun `listDirectory resolves blank path to workspace root`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/workspace.list" -> respond(ok("""{"items":[{"id":"ws-1","path":"/home/leo-tkp/workspace"}]}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("""{"entries":[{"name":"src","type":"directory"}]}"""), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val nodes = client(engine).listDirectory(conn, path = "")
        assertEquals(listOf("src"), nodes.map { it.name })
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/workspace.list", "/api/host.listDirectory"), paths)
        // 根路径解析后作为 host.listDirectory 的 path 参数
        val listPayload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).last())).jsonObject["payload"]!!.jsonObject
        assertEquals("/home/leo-tkp/workspace", listPayload["path"]!!.jsonPrimitive.content)
    }

    /** workspace.list 无条目 → 兜底 host.describe cwd。 */
    @Test
    fun `listDirectory falls back to host describe cwd when no workspaces`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/workspace.list" -> respond(ok("""{"items":[]}"""), HttpStatusCode.OK, jsonHeaders())
                "/api/host.describe" -> respond(ok("""{"version":"0.0.1","cwd":"/srv/home"}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("""{"entries":[]}"""), HttpStatusCode.OK, jsonHeaders())
            }
        }
        client(engine).listDirectory(conn, path = "")
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/workspace.list", "/api/host.describe", "/api/host.listDirectory"), paths)
        val listPayload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).last())).jsonObject["payload"]!!.jsonObject
        assertEquals("/srv/home", listPayload["path"]!!.jsonPrimitive.content)
    }

    /** 调用方带具体 directory（如会话 cwd）→ 优先于 workspace 注册表（零额外 RPC）。 */
    @Test
    fun `listDirectory prefers explicit directory param for blank path`() = runTest {
        val engine = MockEngine { respond(ok("""{"entries":[]}"""), HttpStatusCode.OK, jsonHeaders()) }
        client(engine).listDirectory(conn, path = "", directory = "/w/custom")
        val req = captureRequests(engine).single()
        assertEquals("/api/host.listDirectory", req.url.encodedPath)
        val listPayload = json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject
        assertEquals("/w/custom", listPayload["path"]!!.jsonPrimitive.content)
    }

    /** 根路径解析结果缓存：两次空 path 请求只查一次 workspace.list。 */
    @Test
    fun `listDirectory caches resolved root per connection`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/workspace.list" -> respond(ok("""{"items":[{"id":"ws-1","path":"/root-ws"}]}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("""{"entries":[]}"""), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val api = client(engine)
        api.listDirectory(conn, path = "")
        api.listDirectory(conn, path = "")
        val workspaceCalls = captureRequests(engine).count { it.url.encodedPath == "/api/workspace.list" }
        assertEquals(1, workspaceCalls)
        assertEquals(2, captureRequests(engine).count { it.url.encodedPath == "/api/host.listDirectory" })
    }

    private fun jsonHeaders() = headersOf("Content-Type" to listOf("application/json"))
}
