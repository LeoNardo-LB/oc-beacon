package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.dto.request.ServerConfigPatch
import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    // #318：DshApiClient 构造注入 DshProtocolSource（语义分支）；DshRpcClient 侧
    // 线面翻译/cookie 走 mockk 注册表替身——两路必须同源（同一 protocol/cookie）。
    // 默认 protocol=null（未探测 → 保守 V011）承接既有 0.1.1 回归组。
    private fun client(
        engine: MockEngine,
        protocol: DshWireProtocol? = null,
        cookie: String? = null,
    ): DshApiClient {
        val registry = mockk<DshConnectionRegistry>(relaxed = true)
        every { registry.protocolOf(any()) } returns protocol
        every { registry.cookieHeader(any()) } returns cookie
        return DshApiClient(
            DshRpcClient(ApiClient(HttpClient(engine), json), registry),
            FixedProtocolSource(protocol),
        )
    }

    /** 测试假协议源：恒返回固定线面版本（null = 未探测 → 调用方保守 V011）。 */
    private class FixedProtocolSource(private val protocol: DshWireProtocol?) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
    }

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
    fun `createSession echo without blank maps to blank session`() = runTest {
        // 活体形状（2026-08-31）：session.create 回显 {sessionId, agentPreset}——无 blank 字段。
        // 刚创建会话按定义 blank（事件流无 turn/start）；否则空白页预设卡在首次
        // 点卡（ensureSession 落地）后即消失、无法反复换档（真机实证回归）。
        val engine = MockEngine { respond(ok("""{"sessionId":"session-new-1","agentPreset":"code"}"""), HttpStatusCode.OK, jsonHeaders()) }
        val session = client(engine).createSession(conn, title = null, parentId = null, directory = "/tmp")
        assertTrue(session.blank)
        assertEquals("session-new-1", session.id)
        assertEquals("code", session.agentPreset)
    }

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
    fun `fetchSessionStatus probes session list for liveness`() = runTest {
        // #278：数据源改 session.list（running 播种）——探活语义保留（200 即成功）
        val engine = MockEngine { respond(ok("{\"items\":[]}"), HttpStatusCode.OK, jsonHeaders()) }
        val result = client(engine).fetchSessionStatus(conn)
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
        assertEquals("/api/session.list", captureRequests(engine).single().url.encodedPath)
    }

    @Test
    fun `readAttachment returns media type and base64 pair`() = runTest {
        // #287：value = {attachment:{mediaType,…}, data:"<base64>"}（无 data: 前缀）
        val engine = MockEngine {
            respond(
                ok("""{"attachment":{"attachmentId":"a-1","mediaType":"image/png","bytes":4,"width":2,"height":2,"name":"dot.png"},"data":"aGk="}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val pair = client(engine).readAttachment(conn, "s-1", "a-1")
        assertEquals("image/png" to "aGk=", pair)
        val req = captureRequests(engine).single()
        assertEquals("/api/session.attachment", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session.attachment", body["method"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("a-1", payload["attachmentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `readAttachment missing data degrades to null`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"attachment":{"attachmentId":"a-1","mediaType":"image/png"}}"""), HttpStatusCode.OK, jsonHeaders())
        }
        assertNull(client(engine).readAttachment(conn, "s-1", "a-1"))
    }

    @Test
    fun `readAttachment transport failure degrades to null`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        assertNull(client(engine).readAttachment(conn, "s-1", "a-1"))
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

    /** #309 批1④：忙碌长按发送键——直发插话（session.prompt mode=steer，注入进行中轮次）。 */
    @Test
    fun `promptAsync steer long-press posts mode steer`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        client(engine).promptAsync(
            conn, "s-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "mid-flight")),
            steer = true,
        )
        val payload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject["payload"]!!.jsonObject
        assertEquals("steer", payload["mode"]!!.jsonPrimitive.content)
    }

    /**
     * #276 后端接口补充 + #297 通道勘误：compact 走 commands/execute 命令通道
     * （部署版 session.prompt 无斜杠派发——prompt 文本块 "/compact" 会变
     * user/message 进模型；官方先例 client.js:7366 同走 commands/execute）。
     * kind:"success" → true；providerId/modelId 对 DSH 无效（签名兼容保留）。
     */
    @Test
    fun `compactSession sends compact via commands execute`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"commandId":"c-4","result":{"kind":"success"}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        assertTrue(client(engine).compactSession(conn, "s-1", "ignored-provider", "ignored-model"))
        val req = captureRequests(engine).single()
        assertEquals("/api/commands/execute", req.url.encodedPath)
        val args = json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("s-1", args["agentId"]!!.jsonPrimitive.content)
        assertEquals("/compact", args["line"]!!.jsonPrimitive.content)
    }

    /**
     * 压缩不可用（活跃压缩中/agent 非 idle → kind:"error"）→ 抛 DshApiError
     * （command-error）——repository 收编失败、UI 失败 snackbar（静默失败不可接受）。
     */
    @Test
    fun `compactSession throws on compaction unavailable`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"commandId":"c-5","result":{"kind":"error","text":"Compaction is unavailable because this process has an active compaction, or the agent is not idle."}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val outcome = runCatching { client(engine).compactSession(conn, "s-1", "p", "m") }
        assertTrue(outcome.isFailure)
        assertEquals("command-error", (outcome.exceptionOrNull() as DshApiError).code?.wire)
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

    /**
     * #308（2026-09-03 wire 定音）：/api/respond 请求 = client-response 信封
     * （rpcId=requested 帧稳定 id），value 载荷三键 {sessionId,approvalId,outcome}；
     * 回执 = RpcReceipt {accepted,reason}（非信封）。旧测试用信封回执 mock
     * 掩盖了真实回执形状（#308 根因之一）——本组按服务端 schema 对齐
     * （dsh-host-apiproxy respond impl :3726-3781 + approvalResponsePayloadSchema
     * :678-682 + matchesQuestions :1306-1326）。
     */
    @Test
    fun `replyToPermission posts three-key payload with frame rpcId and parses receipt`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(
            client(engine).replyToPermission(conn, "ses-1", "appr-9", "once", metadata = mapOf("rpcId" to "frame-7")),
        )
        val req = captureRequests(engine).single()
        assertEquals("/api/respond", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("client-response", body["type"]!!.jsonPrimitive.content)
        assertEquals("frame-7", body["rpcId"]!!.jsonPrimitive.content)
        val value = body["result"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("ses-1", value["sessionId"]!!.jsonPrimitive.content)
        assertEquals("appr-9", value["approvalId"]!!.jsonPrimitive.content)
        assertEquals("allowed-once", value["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replyToPermission maps always to allowed-once and reject to rejected`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        val c = client(engine)
        assertTrue(c.replyToPermission(conn, "ses-1", "a1", "always"))
        assertTrue(c.replyToPermission(conn, "ses-1", "a2", "reject"))
        val outcomes = captureRequests(engine).map { req ->
            json.parseToJsonElement(bodyTextOf(req)).jsonObject["result"]!!.jsonObject["value"]!!.jsonObject["outcome"]!!.jsonPrimitive.content
        }
        // allowed-always 在 dsh 0.1.1-rc.2 全树零命中：always=本地规则模拟 + allowed-once 重答
        assertEquals(listOf("allowed-once", "rejected"), outcomes)
    }

    @Test
    fun `replyToPermission falls back rpcId to requestId and fails on rejected receipt`() = runTest {
        val engine = MockEngine { respond("""{"accepted":false,"reason":"not-pending"}""", HttpStatusCode.OK, jsonHeaders()) }
        assertFalse(client(engine).replyToPermission(conn, "ses-1", "appr-9", "once", metadata = null))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("appr-9", body["rpcId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replyToQuestion posts session-scoped answers array with per-item ids`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        val question = QuestionFixture(
            questions = listOf(
                Q(header = "h1", question = "pick one", multiple = false, key = "qi-1", options = listOf(Opt("A"), Opt("B"))),
                Q(header = "h2", question = "pick many", multiple = true, key = "qi-2", options = listOf(Opt("X"), Opt("Y"))),
            ),
        )
        assertTrue(client(engine).replyToQuestion(conn, "frame-q", listOf(listOf("A"), listOf("X", "free text")), question = question))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("frame-q", body["rpcId"]!!.jsonPrimitive.content)
        val value = body["result"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("ses-2", value["sessionId"]!!.jsonPrimitive.content)
        val answers = value["answer"]!!.jsonObject["answers"]!!.jsonArray
        assertEquals("qi-1", answers[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("A"), answers[0].jsonObject["selected"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 多选：label 进 selected，非 label 文本进 custom
        assertEquals(listOf("X"), answers[1].jsonObject["selected"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("free text", answers[1].jsonObject["custom"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replyToQuestion custom on single-select empties selected`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        val question = QuestionFixture(
            questions = listOf(Q(header = "h", question = "q", multiple = false, key = "qi-1", options = listOf(Opt("A")))),
        )
        assertTrue(client(engine).replyToQuestion(conn, "frame-q", listOf(listOf("typed answer")), question = question))
        val answers = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single()))
            .jsonObject["result"]!!.jsonObject["value"]!!.jsonObject["answer"]!!.jsonObject["answers"]!!.jsonArray
        // matchesQuestions 契约：单选 + custom → selected 必空
        assertEquals(0, answers[0].jsonObject["selected"]!!.jsonArray.size)
        assertEquals("typed answer", answers[0].jsonObject["custom"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replyToQuestion with null question returns false without http`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        assertFalse(client(engine).replyToQuestion(conn, "frame-q", listOf(listOf("A")), question = null))
        assertTrue(captureRequests(engine).isEmpty())
    }

    /**
     * #314 根因回归：stub 曾以 emptyList 冒充「服务器权威回答：无待答」→ 进会话
     * loadPendingQuestions 把 SSE 学到的 pending 清空（pre-existing 卡不渲染，三复现）。
     * 修正语义：**null = 端点缺席**（DSH 无 GET /question|/permission REST 面）——
     * 调用方见 null 跳过同步，SSE 存储为唯一权威源（移除走 question|permission resolved 帧）。
     */
    @Test
    fun `listPendingQuestions returns null as endpoint unsupported`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        assertNull(client(engine).listPendingQuestions(conn, null))
        assertTrue(captureRequests(engine).isEmpty()) // 零 HTTP——端点缺席不发请求
    }

    @Test
    fun `listPendingPermissions returns null as endpoint unsupported`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        assertNull(client(engine).listPendingPermissions(conn, null))
        assertTrue(captureRequests(engine).isEmpty())
    }

    @Test
    fun `rejectQuestion posts cancelled error envelope`() = runTest {
        val engine = MockEngine { respond("""{"accepted":true}""", HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).rejectQuestion(conn, "frame-q", null, "ses-2"))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("client-response", body["type"]!!.jsonPrimitive.content)
        assertEquals("frame-q", body["rpcId"]!!.jsonPrimitive.content)
        val result = body["result"]!!.jsonObject
        assertEquals(false, result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("cancelled", result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    private fun QuestionFixture(
        id: String = "frame-q",
        sessionId: String = "ses-2",
        questions: List<dev.leonardo.ocbeacon.domain.model.SseEvent.QuestionAsked.Question>,
    ) = dev.leonardo.ocbeacon.domain.model.SseEvent.QuestionAsked(id = id, sessionId = sessionId, questions = questions)

    private fun Q(header: String, question: String, multiple: Boolean, key: String?, options: List<dev.leonardo.ocbeacon.domain.model.SseEvent.QuestionAsked.Option>) =
        dev.leonardo.ocbeacon.domain.model.SseEvent.QuestionAsked.Question(
            header = header, question = question, multiple = multiple, custom = true, options = options, key = key,
        )

    private fun Opt(label: String) =
        dev.leonardo.ocbeacon.domain.model.SseEvent.QuestionAsked.Option(label = label, description = "")

    // ============ commands/execute + setPermissionPreset（权限预设切换） ============

    /**
     * 活体（perm-10b）：POST /api/commands/execute，payload {args:{agentId,line,images}}；
     * agentId == sessionId（DSH 单 agent 每会话）；images 恒空数组。响应
     * {commandId,result:{kind,text}}，kind=success → true。
     */
    @Test
    fun `executeCommand posts commands execute with args envelope`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"commandId":"c-1","result":{"kind":"success","text":"preset danger-full-access"}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        assertTrue(client(engine).executeCommand(conn, "s-1", "/permission danger-full-access"))
        val req = captureRequests(engine).single()
        assertEquals("/api/commands/execute", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("commands/execute", body["method"]!!.jsonPrimitive.content)
        val args = body["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("s-1", args["agentId"]!!.jsonPrimitive.content)
        assertEquals("/permission danger-full-access", args["line"]!!.jsonPrimitive.content)
        assertEquals(0, args["images"]!!.jsonArray.size)
    }

    /** kind != success（如未知名 → error）→ false。 */
    @Test
    fun `executeCommand treats non-success kind as failure`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"commandId":"c-2","result":{"kind":"error","text":"unknown preset"}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        assertFalse(client(engine).executeCommand(conn, "s-1", "/permission nope"))
    }

    /** setPermissionPreset 封装：line = "/permission <preset>"。 */
    @Test
    fun `setPermissionPreset sends permission slash command`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"commandId":"c-3","result":{"kind":"success","text":"preset workspace-write"}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        assertTrue(client(engine).setPermissionPreset(conn, "s-1", "workspace-write"))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        val args = body["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("/permission workspace-write", args["line"]!!.jsonPrimitive.content)
        assertEquals("s-1", args["agentId"]!!.jsonPrimitive.content)
    }

    // ============ settings.describe / settings.mutate（新会话默认权限档） ============

    private val settingsDescribeValue = """{"writable":true,"hasDocument":true,"namespaces":[
        {"ns":"llm-deepseek","value":{},"revision":1,"applies":"live","secrets":[]},
        {"ns":"permission","value":{"defaultPreset":"danger-full-access"},"revision":42,"applies":"live","secrets":[]}
    ]}""".trimIndent()

    /** #278：DSH 状态播种——session.list running 字段 → busy/idle（原恒空 map）。 */
    @Test
    fun `fetchSessionStatus seeds from session list running`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"items":[{"sessionId":"s1","running":true},{"sessionId":"s2","running":false}]}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val statuses = client(engine).fetchSessionStatus(conn, directory = null).getOrThrow()
        assertEquals("busy", statuses["s1"]?.type)
        assertEquals("idle", statuses["s2"]?.type)
        assertEquals(2, statuses.size)
    }

    /** 活体（perm-4）：settings.describe 的 ns=permission value.defaultPreset + revision。 */
    @Test
    fun `getPermissionDefault parses permission namespace`() = runTest {
        val engine = MockEngine { respond(ok(settingsDescribeValue), HttpStatusCode.OK, jsonHeaders()) }
        val def = client(engine).getPermissionDefault(conn)
        assertNotNull(def)
        assertEquals("danger-full-access", def!!.currentValue)
        assertEquals(42L, def.revision)
        assertEquals("/api/settings.describe", captureRequests(engine).single().url.encodedPath)
    }

    /** #283：ref 解析形态 schema（union→refs→const，活体实测同构）→ 动态档集。 */
    @Test
    fun `getPermissionDefault parses schema enum options`() = runTest {
        val withSchema = settingsDescribeValue.replace(
            "\"ns\":\"permission\",\"value\":{\"defaultPreset\":\"danger-full-access\"},\"revision\":42",
            "\"ns\":\"permission\",\"value\":{\"defaultPreset\":\"danger-full-access\"},\"revision\":42,\"schema\":{\"uid\":3,\"refs\":{\"1\":{\"type\":\"const\",\"value\":\"read-only\"},\"2\":{\"type\":\"const\",\"value\":\"danger-full-access\"},\"3\":{\"type\":\"union\",\"list\":[1,2]}}}",
        )
        val engine = MockEngine { respond(ok(withSchema), HttpStatusCode.OK, jsonHeaders()) }
        val def = client(engine).getPermissionDefault(conn)
        assertNotNull(def)
        assertEquals(listOf("read-only", "danger-full-access"), def!!.options)
        // 无 schema（原 fixture）→ 空档集（UI 回退已知三档常量）
        val engine2 = MockEngine { respond(ok(settingsDescribeValue), HttpStatusCode.OK, jsonHeaders()) }
        assertEquals(emptyList<String>(), client(engine2).getPermissionDefault(conn)!!.options)
    }

    /** 部署未挂 permission 插件（namespaces 无 permission）→ null。 */
    @Test
    fun `getPermissionDefault null when permission namespace absent`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"writable":true,"hasDocument":true,"namespaces":[{"ns":"llm","value":{},"revision":1,"applies":"live","secrets":[]}]}"""), HttpStatusCode.OK, jsonHeaders())
        }
        assertNull(client(engine).getPermissionDefault(conn))
    }

    // ============ #298：settings 特权面 403（非 loopback 连接）============

    /** 活体形态（#298）：Host 栅栏 403 + 纯文本 body "forbidden" → 抛 DshSettingsForbiddenException。 */
    @Test
    fun `getPermissionDefault throws forbidden on 403 describe`() = runTest {
        val engine = MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }
        val outcome = runCatching { client(engine).getPermissionDefault(conn) }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException)
    }

    /** 非 403 失败（5xx）维持静默降级 null——只有栅栏 403 才上抛。 */
    @Test
    fun `getPermissionDefault null on non-403 server error`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        assertNull(client(engine).getPermissionDefault(conn))
    }

    /** 读 agent-presets 默认档同栅栏 → 抛 DshSettingsForbiddenException。 */
    @Test
    fun `getDefaultAgentPreset throws forbidden on 403 describe`() = runTest {
        val engine = MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }
        val outcome = runCatching { client(engine).getDefaultAgentPreset(conn) }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException)
    }

    /** 写路径经 getter 先行读 → describe 403 同样上抛（不静默 false）。 */
    @Test
    fun `setPermissionDefault throws forbidden on 403 describe`() = runTest {
        val engine = MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }
        val outcome = runCatching { client(engine).setPermissionDefault(conn, "read-only") }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException)
    }

    /** settings.mutate 独立受栅栏：describe 放行 + mutate 403 → 上抛。 */
    @Test
    fun `setDefaultAgentPreset throws forbidden when mutate 403`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/settings.describe" -> respond(ok(agentPresetSettingsDescribe), HttpStatusCode.OK, jsonHeaders())
                else -> respond("forbidden", HttpStatusCode.Forbidden)
            }
        }
        val outcome = runCatching { client(engine).setDefaultAgentPreset(conn, "build") }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException)
    }

    /** settings.mutate 写 defaultPreset：ops=[{set,path:[defaultPreset],value}] + expectedRevision。 */
    @Test
    fun `setPermissionDefault mutates defaultPreset with expectedRevision`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/settings.describe" -> respond(ok(settingsDescribeValue), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("""{"ns":"permission","value":{"defaultPreset":"workspace-write"},"revision":43,"applies":"live","secrets":[]}"""), HttpStatusCode.OK, jsonHeaders())
            }
        }
        assertTrue(client(engine).setPermissionDefault(conn, "workspace-write"))
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/settings.describe", "/api/settings.mutate"), paths)
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).last())).jsonObject
        assertEquals("settings.mutate", body["method"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("permission", payload["ns"]!!.jsonPrimitive.content)
        assertEquals(42L, payload["expectedRevision"]!!.jsonPrimitive.content.toLong())
        val op = payload["ops"]!!.jsonArray[0].jsonObject
        assertEquals("set", op["op"]!!.jsonPrimitive.content)
        assertEquals("defaultPreset", op["path"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("workspace-write", op["value"]!!.jsonPrimitive.content)
    }

    // ============ agentPreset.list / agentPreset.select（Agent 预设选择） ============

    private val agentPresetListValue = """{"presets":[
        {"id":"standard","trust":"system","isDefault":false,"name":"Standard","description":"Full baseline"},
        {"id":"code","trust":"system","isDefault":true,"name":"Code","description":"Code Mode SDK"},
        {"id":"minimal","trust":"system","isDefault":false,"name":"Minimal","description":"Dual tools"},
        {"id":"cordis","trust":"system","isDefault":false,"name":"Cordis","description":"Author presets"}
    ],"authorable":true,"hasDocument":true}""".trimIndent()

    /** 活体（ap-1）：agentPreset.list 的 value.presets[{id,name,description,isDefault}] roster。 */
    @Test
    fun `listAgentPresets parses roster entries`() = runTest {
        val engine = MockEngine { respond(ok(agentPresetListValue), HttpStatusCode.OK, jsonHeaders()) }
        val presets = client(engine).listAgentPresets(conn)
        assertEquals(listOf("standard", "code", "minimal", "cordis"), presets.map { it.id })
        assertEquals("Code", presets[1].name)
        assertEquals("Code Mode SDK", presets[1].description)
        assertTrue(presets[1].isDefault)
        assertFalse(presets[0].isDefault)
        assertEquals("/api/agentPreset.list", captureRequests(engine).single().url.encodedPath)
    }

    /** list 失败（如 HTTP 错误）→ 软降级空列表（UI 隐藏卡区）。 */
    @Test
    fun `listAgentPresets degrades to empty on failure`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"internal","message":"boom"}}}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        assertTrue(client(engine).listAgentPresets(conn).isEmpty())
    }

    /**
     * 2026-09-01（走查后修复批·steer 实测发现）：wire 方法名必须是 **session.updateQueue**
     * （服务器方法面 session.<域>.<动作>）——原 "updateQueue" 直发 /api/updateQueue
     * HTTP 404，QueueDock edit/remove/steer 全部静默失效（走查期「remove 可用」实为
     * 步边界消费误判）。本测试钉住方法名与载荷形状防回归。
     */
    @Test
    fun `updateQueue posts session-updateQueue method and action payload`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }

        client(engine).updateQueue(
            conn,
            "session-1",
            "item-1",
            dev.leonardo.ocbeacon.domain.model.QueueActionKind.STEER,
            editText = null,
        )

        val req = captureRequests(engine).single()
        assertEquals("/api/session.updateQueue", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session.updateQueue", body["method"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("session-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("item-1", payload["itemId"]!!.jsonPrimitive.content)
        assertEquals("steer", payload["action"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    /** 活体（ap-5）：select payload {sessionId, agentPreset}，成功 value={agentPreset}。 */
    @Test
    fun `selectAgentPreset posts select envelope with sessionId and agentPreset`() = runTest {
        val engine = MockEngine { respond(ok("""{"agentPreset":"standard"}"""), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).selectAgentPreset(conn, "s-1", "standard"))
        val req = captureRequests(engine).single()
        assertEquals("/api/agentPreset.select", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("agentPreset.select", body["method"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("standard", payload["agentPreset"]!!.jsonPrimitive.content)
    }

    /** 非 blank select → agent-preset-locked（DshApiError.code 分类 Busy）。 */
    @Test
    fun `selectAgentPreset maps locked error to busy category`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"agent-preset-locked","message":"preset is fixed"}}}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val outcome = runCatching { client(engine).selectAgentPreset(conn, "s-1", "code") }
        assertTrue(outcome.isFailure)
        val err = outcome.exceptionOrNull() as DshApiError
        assertEquals("agent-preset-locked", err.code?.wire)
        assertEquals(DshErrorCategory.Busy, err.category)
    }

    /** 未知 id → agent-preset-not-found（DshApiError.code 分类 NotFound）。 */
    @Test
    fun `selectAgentPreset maps not found error to notfound category`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"agent-preset-not-found","message":"unknown"}}}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val outcome = runCatching { client(engine).selectAgentPreset(conn, "s-1", "nope") }
        val err = outcome.exceptionOrNull() as DshApiError
        assertEquals("agent-preset-not-found", err.code?.wire)
        assertEquals(DshErrorCategory.NotFound, err.category)
    }

    // ============ settings.describe / settings.mutate（新会话默认 Agent 预设） ============

    private val agentPresetSettingsDescribe = """{"writable":true,"hasDocument":true,"namespaces":[
        {"ns":"agent-presets","value":{"default":"code"},"revision":3,"applies":"live","secrets":[]}
    ]}""".trimIndent()

    /** 活体（ap-2）：settings.describe ns=agent-presets 的 value.default + revision。 */
    @Test
    fun `getDefaultAgentPreset parses agent-presets namespace`() = runTest {
        val engine = MockEngine { respond(ok(agentPresetSettingsDescribe), HttpStatusCode.OK, jsonHeaders()) }
        val def = client(engine).getDefaultAgentPreset(conn)
        assertNotNull(def)
        assertEquals("code", def!!.currentValue)
        assertEquals(3L, def.revision)
        assertEquals("/api/settings.describe", captureRequests(engine).single().url.encodedPath)
    }

    /** 部署未挂 agent-presets 插件（namespaces 无该 ns）→ null。 */
    @Test
    fun `getDefaultAgentPreset null when namespace absent`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"writable":true,"hasDocument":true,"namespaces":[{"ns":"llm","value":{},"revision":1,"applies":"live","secrets":[]}]}"""), HttpStatusCode.OK, jsonHeaders())
        }
        assertNull(client(engine).getDefaultAgentPreset(conn))
    }

    /** settings.mutate 写 default：ops=[{set,path:[default],value}] + expectedRevision。 */
    @Test
    fun `setDefaultAgentPreset mutates default with expectedRevision`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/settings.describe" -> respond(ok(agentPresetSettingsDescribe), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("""{"ns":"agent-presets","value":{"default":"minimal"},"revision":4,"applies":"live","secrets":[]}"""), HttpStatusCode.OK, jsonHeaders())
            }
        }
        assertTrue(client(engine).setDefaultAgentPreset(conn, "minimal"))
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/settings.describe", "/api/settings.mutate"), paths)
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).last())).jsonObject
        val payload = body["payload"]!!.jsonObject
        assertEquals("agent-presets", payload["ns"]!!.jsonPrimitive.content)
        assertEquals(3L, payload["expectedRevision"]!!.jsonPrimitive.content.toLong())
        val op = payload["ops"]!!.jsonArray[0].jsonObject
        assertEquals("set", op["op"]!!.jsonPrimitive.content)
        assertEquals("default", op["path"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("minimal", op["value"]!!.jsonPrimitive.content)
    }

    // ============ subagent.list（AgentSheet 多级树权威域，12 号活体证据） ============

    private val subagentCatalogValue = """{"entries":[
        {"kind":"child","id":"c-1","mode":"continuable","label":"调研输入法云组件方案","activity":"running","hasChildren":true},
        {"kind":"child","id":"c-2","mode":"one-shot","activity":"inactive","hasChildren":false},
        {"kind":"diagnostic","id":"c-3","reason":"corrupt"}
    ],"parentAvailable":false}""".trimIndent()

    /**
     * 活体实录（2026-09-25 探测）：POST /api/subagent.list，payload
     * {"parentSessionId":...}；value = {entries:[...], parentAvailable}。
     * 逐字段映射：kind/id/mode/activity/hasChildren/label（one-shot 可选）；
     * diagnostic 行（corrupt/unsupported/unavailable）只有 kind/id/reason。
     * parentSessionId 也接受裸子会话 id（L2 懒加载，f1d037e3-… 实证）。
     */
    @Test
    fun `listSubagentCatalog calls subagent list and maps entries`() = runTest {
        val engine = MockEngine { respond(ok(subagentCatalogValue), HttpStatusCode.OK, jsonHeaders()) }
        val entries = client(engine).listSubagentCatalog(conn, "session-root-1")
        assertEquals(3, entries.size)
        assertEquals("child", entries[0].kind)
        assertEquals("c-1", entries[0].id)
        assertEquals("continuable", entries[0].mode)
        assertEquals("running", entries[0].activity)
        assertTrue(entries[0].hasChildren)
        assertEquals("调研输入法云组件方案", entries[0].label)
        assertNull(entries[0].reason)
        // one-shot label 可选（spec：one-shot label optional）
        assertNull(entries[1].label)
        // diagnostic 行：reason 保留，无 mode/activity/hasChildren
        assertEquals("diagnostic", entries[2].kind)
        assertEquals("corrupt", entries[2].reason)
        val req = captureRequests(engine).single()
        assertEquals("/api/subagent.list", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("subagent.list", body["method"]!!.jsonPrimitive.content)
        assertEquals("session-root-1", body["payload"]!!.jsonObject["parentSessionId"]!!.jsonPrimitive.content)
    }

    /** 业务错误恒 HTTP 200 + result.error（subagent-not-found 实测码）——上抛供软降级判定。 */
    @Test
    fun `listSubagentCatalog propagates rpc error`() = runTest {
        val engine = MockEngine { respond(err("subagent-not-found", "no such child"), HttpStatusCode.OK, jsonHeaders()) }
        val outcome = runCatching { client(engine).listSubagentCatalog(conn, "no-such") }
        assertTrue(outcome.isFailure)
        assertEquals("subagent-not-found", (outcome.exceptionOrNull() as DshApiError).code?.wire)
    }

    // ============ #310① 子智能体续聊（subagents/prompt·interruptByParent·list 整帧） ============

    /**
     * V012 subagents/prompt（#310① wire 契约钉死 2026-09-05）：URL/信封方法名
     * subagents/prompt；载荷 {args:{request:{requestId(UUID),parentSessionId,
     * childSessionId,mode:"continuable" 固定,content:[PromptContentPart],
     * clientTimeZone?}}}——与主会话 session/prompt 的差异即双会话地址 + 恒定
     * mode（zod literal("continuable")，无 queue/steer 档位）。回执 {messageId}。
     */
    @Test
    fun `v012 subagentPrompt posts continuable mode with parent and child ids`() = runTest {
        val engine = MockEngine { respond(ok("""{"messageId":"msg-sub-1"}"""), HttpStatusCode.OK, jsonHeaders()) }
        val messageId = client(engine, DshWireProtocol.V012).subagentPrompt(
            conn, "parent-1", "child-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "继续")),
            clientTimeZone = "Asia/Shanghai",
        )
        assertEquals("msg-sub-1", messageId)
        val req = captureRequests(engine).single()
        assertEquals("/api/subagents/prompt", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("subagents/prompt", body["method"]!!.jsonPrimitive.content)
        val request = body["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        // 双会话地址：parent + child（与 session.prompt 单 sessionId 的差异）
        assertEquals("parent-1", request["parentSessionId"]!!.jsonPrimitive.content)
        assertEquals("child-1", request["childSessionId"]!!.jsonPrimitive.content)
        // mode 恒 continuable（服务端 zod literal——无 queue/steer 档位）
        assertEquals("continuable", request["mode"]!!.jsonPrimitive.content)
        assertEquals("Asia/Shanghai", request["clientTimeZone"]!!.jsonPrimitive.content)
        val requestId = request["requestId"]!!.jsonPrimitive.content
        assertEquals(36, requestId.length)
        assertTrue("requestId 必须可解析为 UUID", runCatching { java.util.UUID.fromString(requestId) }.isSuccess)
        val text = request["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("继续", text["text"]!!.jsonPrimitive.content)
    }

    /**
     * V012 subagents/interruptByParent：三平铺参
     * {args:{childSessionId,parentSessionId,mode:"continuable"}}（FLAT 端点）；
     * 回执 {accepted:true}。durable 父址中断——父 Agent 不在线也能中断。
     */
    @Test
    fun `v012 subagentInterrupt posts interruptByParent flat args`() = runTest {
        val engine = MockEngine { respond(ok("""{"accepted":true}"""), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine, DshWireProtocol.V012).subagentInterrupt(conn, "parent-1", "child-1"))
        val req = captureRequests(engine).single()
        assertEquals("/api/subagents/interruptByParent", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("subagents/interruptByParent", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"childSessionId":"child-1","parentSessionId":"parent-1","mode":"continuable"}}""",
            body["payload"].toString(),
        )
    }

    /**
     * subagentCatalog 域投影（#310①）：subagents/list 整帧——entries 逐行映射
     * （含 mode/activity/hasChildren/diagnostic reason）+ parentAvailable
     * （父 Agent 不在线时续聊 prompt 将拒 subagent/parent-unavailable）。
     */
    @Test
    fun `v012 subagentCatalog maps entries with mode and parentAvailable`() = runTest {
        val engine = MockEngine { respond(ok(subagentCatalogValue), HttpStatusCode.OK, jsonHeaders()) }
        val catalog = client(engine, DshWireProtocol.V012).subagentCatalog(conn, "session-root-1")
        assertFalse(catalog.parentAvailable)
        assertEquals(3, catalog.entries.size)
        // child 行：mode/activity/hasChildren 全保真（one-shot 禁 composer 留给 AgentSheet 刷新迭代）
        assertEquals("continuable", catalog.entries[0].mode)
        assertEquals("child", catalog.entries[0].kind)
        assertEquals("running", catalog.entries[0].activity)
        assertTrue(catalog.entries[0].hasChildren)
        assertEquals("one-shot", catalog.entries[1].mode)
        assertEquals("inactive", catalog.entries[1].activity)
        // diagnostic 行：kind 判别 + reason 保留
        assertTrue(catalog.entries[2].isDiagnostic)
        assertEquals("corrupt", catalog.entries[2].reason)
        val req = captureRequests(engine).single()
        assertEquals("/api/subagents/list", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("subagents/list", body["method"]!!.jsonPrimitive.content)
        assertEquals("""{"args":{"parentSessionId":"session-root-1"}}""", body["payload"].toString())
    }

    /** 保守 V011：subagents 域不在 0.1.1 方法面（#310 审计裁决）——双方法 unsupported 且零 HTTP。 */
    @Test
    fun `subagent prompt and interrupt throw unsupported on v011`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        val c = client(engine) // 未探测 → 保守 V011
        val prompt = runCatching {
            c.subagentPrompt(conn, "parent-1", "child-1", listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "x")))
        }
        assertTrue(prompt.isFailure)
        assertTrue(prompt.exceptionOrNull() is UnsupportedServerCapability)
        val interrupt = runCatching { c.subagentInterrupt(conn, "parent-1", "child-1") }
        assertTrue(interrupt.isFailure)
        assertTrue(interrupt.exceptionOrNull() is UnsupportedServerCapability)
        assertEquals(0, captureRequests(engine).size)
    }

    // ============ #310② 消息反馈（messageFeedback/put·delete·list） ============

    /**
     * V012 messageFeedback/put（#310② wire 契约钉死 2026-09-05 §②）：URL/信封
     * 方法名 messageFeedback/put；载荷
     * {args:{request:{sessionId,messageId,rating,note?,ifVersion:version|null}}}（单 request
     * 对象参 → WRAPPED 包装）。**业务结果驱 RPC value**（web mod37
     * put:188 先例：carried.value 才是 {ok,value|error} 二态）——
     * 成功 value=整项，业务拒绝 value={ok:false,error} 不走信封 error。
     */
    @Test
    fun `v012 messageFeedbackPut posts null ifVersion and maps committed item`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"ok":true,"value":{"messageId":"msg_1","rating":"positive","note":"good","version":"11111111-111-4111-8111-111111111111","createdAt":1000,"updatedAt":2000}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).messageFeedbackPut(
            conn, "s-1", "msg_1", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive,
            note = "good", ifVersion = null,
        )
        val item = (result as dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Success).item
        assertEquals("msg_1", item.messageId)
        assertEquals(dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive, item.rating)
        assertEquals("good", item.note)
        assertEquals("11111111-111-4111-8111-111111111111", item.version)
        assertEquals(1000L, item.createdAt)
        assertEquals(2000L, item.updatedAt)
        val req = captureRequests(engine).single()
        assertEquals("/api/messageFeedback/put", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("messageFeedback/put", body["method"]!!.jsonPrimitive.content)
        val request = body["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("s-1", request["sessionId"]!!.jsonPrimitive.content)
        assertEquals("msg_1", request["messageId"]!!.jsonPrimitive.content)
        assertEquals("positive", request["rating"]!!.jsonPrimitive.content)
        assertEquals("good", request["note"]!!.jsonPrimitive.content)
        // 未评断言：ifVersion 必须是 JSON null（而非缺席——zod 区分 null 与 undefined）
        assertTrue(request["ifVersion"] is kotlinx.serialization.json.JsonNull)
    }

    /** 已观察到的项换向：ifVersion 携带 CAS token；note 缺席不发键。 */
    @Test
    fun `v012 messageFeedbackPut carries observed version and omits blank note`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"ok":true,"value":{"messageId":"msg_1","rating":"negative","version":"22222222-2222-4222-8222-222222222222","createdAt":1,"updatedAt":2}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).messageFeedbackPut(
            conn, "s-1", "msg_1", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Negative,
            note = null, ifVersion = "22222222-2222-4222-8222-222222222222",
        )
        assertTrue(result is dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Success)
        val request = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single()))
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("22222222-2222-4222-8222-222222222222", request["ifVersion"]!!.jsonPrimitive.content)
        assertNull(request["note"])
    }

    /**
     * version-conflict 判别（CAS 核心）：业务拒绝码 + 服务器权威 current
     * （整项或 null）供重同步——web 先例以 error.current 覆盖本地观察后重试。
     */
    @Test
    fun `v012 messageFeedbackPut conflict surfaces authoritative current item or absence`() = runTest {
        val withCurrent = ok(
            """{"ok":false,"error":{"code":"version-conflict","current":{"messageId":"msg_1","rating":"negative","version":"v-2","createdAt":1,"updatedAt":2} }}""",
        )
        val engine = MockEngine { respond(withCurrent, HttpStatusCode.OK, jsonHeaders()) }
        val conflicted = client(engine, DshWireProtocol.V012).messageFeedbackPut(
            conn, "s-1", "msg_1", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive,
            ifVersion = "v-1",
        )
        val conflict = conflicted as dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.VersionConflict
        assertEquals(dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Negative, conflict.current!!.rating)
        assertEquals("v-2", conflict.current.version)

        val absentCurrent = ok("""{"ok":false,"error":{"code":"version-conflict","current":null}}""")
        val engine2 = MockEngine { respond(absentCurrent, HttpStatusCode.OK, jsonHeaders()) }
        val conflicted2 = client(engine2, DshWireProtocol.V012).messageFeedbackPut(
            conn, "s-1", "msg_1", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive,
            ifVersion = "v-1",
        )
        assertNull(
            (conflicted2 as dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.VersionConflict).current,
        )
    }

    /** 其他业务失败码（target-not-found 实服务器硬校验码）→ Failure 分支保留原串码。 */
    @Test
    fun `v012 messageFeedbackPut business rejection maps to failure branch`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"ok":false,"error":{"code":"target-not-found","sessionId":"s-1","messageId":"msg_x"}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).messageFeedbackPut(
            conn, "s-1", "msg_x", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive,
            ifVersion = null,
        )
        val failure = result as dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Failure
        assertEquals("target-not-found", failure.code)
    }

    /** 信封级错误（搬运层，如 bad-request）同样收编进 Failure——不向 UI 上抛。 */
    @Test
    fun `v012 messageFeedbackPut envelope error folds into failure branch`() = runTest {
        val engine = MockEngine { respond(err("bad-request", "arguments invalid"), HttpStatusCode.OK, jsonHeaders()) }
        val result = client(engine, DshWireProtocol.V012).messageFeedbackPut(
            conn, "s-1", "msg_1", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive,
            ifVersion = null,
        )
        assertEquals("bad-request", (result as dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult.Failure).code)
    }

    /** V012 messageFeedback/delete：载荷 {args:{request:{sessionId,messageId,ifVersion}}}；幂等回执 {absent:true}。 */
    @Test
    fun `v012 messageFeedbackDelete posts observed version and maps idempotent absent`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"ok":true,"value":{"absent":true}}"""), HttpStatusCode.OK, jsonHeaders())
        }
        val result = client(engine, DshWireProtocol.V012).messageFeedbackDelete(
            conn, "s-1", "msg_1", ifVersion = "v-9",
        )
        assertTrue(result is dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.Absent)
        val req = captureRequests(engine).single()
        assertEquals("/api/messageFeedback/delete", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("messageFeedback/delete", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"sessionId":"s-1","messageId":"msg_1","ifVersion":"v-9"}""",
            body["payload"]!!.jsonObject["args"]!!.jsonObject["request"].toString(),
        )
    }

    /** delete 同样可 version-conflict（既存项版本不匹配）——判别并携带 current。 */
    @Test
    fun `v012 messageFeedbackDelete conflict surfaces current`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"ok":false,"error":{"code":"version-conflict","current":{"messageId":"msg_1","rating":"positive","version":"v-3","createdAt":1,"updatedAt":2}}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine, DshWireProtocol.V012).messageFeedbackDelete(
            conn, "s-1", "msg_1", ifVersion = "v-stale",
        )
        val conflict = result as dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult.VersionConflict
        assertEquals("v-3", conflict.current!!.version)
    }

    /** V012 messageFeedback/list：载荷 {args:{request:{sessionId}}}；回执 {items:[...]} 逐项映射。 */
    @Test
    fun `v012 messageFeedbackList posts sessionId and maps items`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""{"ok":true,"value":{"items":[
                    {"messageId":"msg_1","rating":"positive","note":"helpful","version":"v-1","createdAt":10,"updatedAt":20},
                    {"messageId":"msg_2","rating":"negative","version":"v-2","createdAt":30,"updatedAt":40}
                ]}}"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val items = client(engine, DshWireProtocol.V012).messageFeedbackList(conn, "s-1")
        assertEquals(listOf("msg_1", "msg_2"), items.map { it.messageId })
        assertEquals("helpful", items[0].note)
        assertNull(items[1].note)
        assertEquals(dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Negative, items[1].rating)
        val req = captureRequests(engine).single()
        assertEquals("/api/messageFeedback/list", req.url.encodedPath)
        assertEquals(
            """{"sessionId":"s-1"}""",
            json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"].toString(),
        )
    }

    /** 保守 V011：messageFeedback 域不在 0.1.1 方法面（#310 审计裁决）——三方法 unsupported 且零 HTTP。 */
    @Test
    fun `message feedback methods throw unsupported on v011`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        val c = client(engine) // 未探测 → 保守 V011
        val put = runCatching {
            c.messageFeedbackPut(conn, "s-1", "msg_1", dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive, ifVersion = null)
        }
        assertTrue(put.exceptionOrNull() is UnsupportedServerCapability)
        val delete = runCatching { c.messageFeedbackDelete(conn, "s-1", "msg_1", ifVersion = "v-1") }
        assertTrue(delete.exceptionOrNull() is UnsupportedServerCapability)
        val list = runCatching { c.messageFeedbackList(conn, "s-1") }
        assertTrue(list.exceptionOrNull() is UnsupportedServerCapability)
        assertEquals(0, captureRequests(engine).size)
    }

    // ============ #310⑤/#321 @ 引用候选（fileReferences/list · sessionReferenceResolver/candidates） ============

    /**
     * V012 fileReferences/list（#321 wire 契约钉死 2026-09-05 §⑤）：两平铺参
     * {args:{agentId,query}}（typert 参数名即 wire 键，FLAT——无 request 包装）；
     * value = FileReferenceCandidate[] [{path,kind:'file'|'directory'}] 直返数组
     * （callJson 语义，commands/list 先例）。kind 现阶段丢弃（UI 以尾 / 约定区分
     * 目录，FileMentionSuggestions 现状）；缺 path 行丢弃（容错先例）。
     */
    @Test
    fun `v012 fileReferencesList posts flat agentId and query and maps paths`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""[{"path":"docs/a.md","kind":"file"},{"path":"docs/sub","kind":"directory"},{"kind":"file"}]"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val paths = client(engine, DshWireProtocol.V012).fileReferencesList(conn, "s-1", "docs")
        assertEquals(listOf("docs/a.md", "docs/sub"), paths)
        val req = captureRequests(engine).single()
        assertEquals("/api/fileReferences/list", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("fileReferences/list", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-1","query":"docs"}}""",
            body["payload"].toString(),
        )
    }

    /**
     * V012 sessionReferenceResolver/candidates（#310⑤）：同两平铺参
     * {args:{agentId,query}}（FLAT）；value = SessionReferenceMentionCandidate[]
     * 直返数组 [{sessionId,label,cwd?,sameWorkspace,createdAt,mention}]——
     * mention 为服务器权威规范串 @[label](dsh-session:…)（客户端不重组）；
     * createdAt 现阶段丢弃（合并排序按 sameWorkspace）；缺征意义字段
     * （sessionId/label/mention）行丢弃。
     */
    @Test
    fun `v012 sessionReferenceCandidates posts flat args and maps mention rows`() = runTest {
        val engine = MockEngine {
            respond(
                ok("""[
                    {"mention":"@[修复 X](dsh-session:s-9)","sessionId":"s-9","label":"修复 X","cwd":"/w","sameWorkspace":true,"createdAt":1788109000023},
                    {"sessionId":"s-8","label":"别区会话","sameWorkspace":false,"createdAt":2,"mention":"@[别区会话](dsh-session:s-8)"},
                    {"label":"缺 id","sameWorkspace":false,"mention":"m"}
                ]"""),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val sessions = client(engine, DshWireProtocol.V012).sessionReferenceCandidates(conn, "s-1", "fix")
        assertEquals(listOf("s-9", "s-8"), sessions.map { it.sessionId })
        assertEquals("修复 X", sessions[0].label)
        assertEquals("/w", sessions[0].cwd)
        assertTrue(sessions[0].sameWorkspace)
        assertEquals("@[修复 X](dsh-session:s-9)", sessions[0].mention)
        assertNull(sessions[1].cwd)
        assertFalse(sessions[1].sameWorkspace)
        val req = captureRequests(engine).single()
        assertEquals("/api/sessionReferenceResolver/candidates", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("sessionReferenceResolver/candidates", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-1","query":"fix"}}""",
            body["payload"].toString(),
        )
    }

    /** 保守 V011：两引用端点不在 0.1.1 方法面（#310 审计裁决）——unsupported 且零 HTTP。 */
    @Test
    fun `mention reference endpoints throw unsupported on v011`() = runTest {
        val engine = MockEngine { respond(ok("[]"), HttpStatusCode.OK, jsonHeaders()) }
        val c = client(engine) // 未探测 → 保守 V011
        val files = runCatching { c.fileReferencesList(conn, "s-1", "q") }
        assertTrue(files.exceptionOrNull() is UnsupportedServerCapability)
        val sessions = runCatching { c.sessionReferenceCandidates(conn, "s-1", "q") }
        assertTrue(sessions.exceptionOrNull() is UnsupportedServerCapability)
        assertEquals(0, captureRequests(engine).size)
    }

    /** W4/D8(2026-09-06 全量 E2E):原生建目录——directoryPicker/createDirectory
     * 平铺 {path,name}(browse 能力,两位置参数名即 wire 键),返回字符串直取。
     * 取代 mkdir 临时会话 shell 通道(DSH 命令注册表无 shell/exec→双回退必败,
     * 且 finally deleteSession 无能力位→临时会话泄漏)。 */
    @Test
    fun `createDirectory sends picker createDirectory flat args and returns path`() = runTest {
        val engine = MockEngine { respond(ok("\"/parent/newdir\""), HttpStatusCode.OK, jsonHeaders()) }
        val path = client(engine, protocol = DshWireProtocol.V012)
            .createDirectory(conn, "/parent", "newdir")
        assertEquals("/parent/newdir", path)
        val req = captureRequests(engine).single()
        assertEquals("/api/directoryPicker/createDirectory", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("directoryPicker/createDirectory", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"path":"/parent","name":"newdir"}}""",
            body["payload"].toString(),
        )
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


    // ============ ProviderApi：llm 目录 + session.selectModel（#276 模型切换接通） ============

    private val llmProvidersValue = """{"providers":[
        {"provider":"deepseek-official","displayName":"DeepSeek","settingsNs":"llm-deepseek","settingsPath":[],"active":true},
        {"provider":"anthropic","displayName":"anthropic","settingsNs":"llm-pi-ai","settingsPath":["providers","anthropic"],"active":false,"declared":false}
    ]}""".trimIndent()

    private val llmModelsValue = """{"groups":[
        {"id":"deepseek-official","name":"DeepSeek","models":[
            {"id":"deepseek-v4-flash","name":"DeepSeek-V4-Flash","reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"low","name":"Low"},{"id":"high","name":"High"}],"defaultEffort":"high"}},
            {"id":"deepseek-v4-pro","name":"DeepSeek-V4-Pro"}
        ]},
        {"id":"opencode-go","name":"opencode-go","models":[
            {"id":"glm-5.3","reasoning":{"efforts":[{"id":"high","name":"High"}]}}
        ]}
    ],"failures":[]}""".trimIndent()

    private fun llmCatalogEngine(
        providersValue: String? = llmProvidersValue,
        modelsValue: String? = llmModelsValue,
    ) = MockEngine { req ->
        when (req.url.encodedPath) {
            "/api/llm.providers" ->
                if (providersValue != null) respond(ok(providersValue), HttpStatusCode.OK, jsonHeaders())
                else respond(err("internal", "providers down"), HttpStatusCode.OK, jsonHeaders())
            "/api/llm.models" ->
                if (modelsValue != null) respond(ok(modelsValue), HttpStatusCode.OK, jsonHeaders())
                else respond(err("internal", "models down"), HttpStatusCode.OK, jsonHeaders())
            else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
        }
    }

    private fun err(code: String, message: String) =
        """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"@CODE@","message":"@MSG@"}}}""".trimIndent()
            .replace("@CODE@", code).replace("@MSG@", message)

    /**
     * 目录映射逐字段（/tmp/dsh-openapi-cases/05、06 活体样本）：
     * llm.providers 条目 {provider, displayName} → id/name（旧 id/name 键防御兼容）；
     * llm.models groups[{id,name,models[{id,name,reasoning{efforts,defaultEffort}}]}] →
     * 组内模型挂到同名 provider；efforts → variants（供 variantNames 思考档位 pill）；
     * 目录未覆盖的组防御性追加（目录序优先）。
     */
    @Test
    fun `getProviders maps llm directory and model groups field by field`() = runTest {
        val response = client(llmCatalogEngine()).getProviders(conn)
        assertEquals(listOf("deepseek-official", "anthropic", "opencode-go"), response.providers.map { it.id })
        // displayName → name
        assertEquals("DeepSeek", response.providers[0].name)
        assertEquals("anthropic", response.providers[1].name)
        // 无模型的目录条目保留（上层 applyProviderFilter 决定去留）；未知组追加
        assertTrue(response.providers[1].models.isEmpty())
        val flash = response.providers[0].models["deepseek-v4-flash"]
        assertNotNull(flash)
        assertEquals("DeepSeek-V4-Flash", flash!!.name)
        assertEquals("deepseek-official", flash.providerId)
        // reasoning.efforts → variants（key=effort id）+ capabilities.reasoning 槽位
        assertEquals(setOf("off", "low", "high"), flash.variants?.keys)
        assertEquals(true, flash.capabilities?.reasoning)
        // 无 reasoning → variants null + capabilities 不标记
        val pro = response.providers[0].models["deepseek-v4-pro"]!!
        assertEquals("DeepSeek-V4-Pro", pro.name)
        assertNull(pro.variants)
        val glm = response.providers[2].models["glm-5.3"]!!
        assertEquals("glm-5.3", glm.name)
        assertEquals(listOf("high"), glm.variants?.keys?.toList())
    }

    /** llm.models 失败软降级（V2 先例：模型端点 runCatching 空）——目录仍完整返回。 */
    @Test
    fun `getProviders soft degrades when llm models fails`() = runTest {
        val response = client(llmCatalogEngine(modelsValue = null)).getProviders(conn)
        assertEquals(listOf("deepseek-official", "anthropic"), response.providers.map { it.id })
        assertTrue(response.providers.all { it.models.isEmpty() })
    }

    /** llm.providers 目录失败——组仍可拼目录（目录序不可得时按组序兜底）。 */
    @Test
    fun `getProviders builds catalog from groups when directory fails`() = runTest {
        val response = client(llmCatalogEngine(providersValue = null)).getProviders(conn)
        assertEquals(listOf("deepseek-official", "opencode-go"), response.providers.map { it.id })
        assertEquals(2, response.providers[0].models.size)
    }

    @Test
    fun `listProviderCatalog returns merged providers`() = runTest {
        val catalog = client(llmCatalogEngine()).listProviderCatalog(conn)
        assertEquals(listOf("deepseek-official", "anthropic", "opencode-go"), catalog.all.map { it.id })
    }

    /**
     * selectModel payload 形状（M03 实测证据）：{sessionId, provider, model,
     * reasoningEffort?}——variant 槽位（思考档位 pill）映射 reasoningEffort；
     * 发送顺序 selectModel → prompt（V2 先例：prompt 前显式切换）。
     */
    @Test
    fun `promptAsync selects model before prompt with reasoning effort`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session.selectModel" -> respond(ok("""{"selected":{"provider":"zai-coding-cn","model":"glm-5.3"}}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        client(engine).promptAsync(
            conn, "s-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "hello")),
            model = dev.leonardo.ocbeacon.data.dto.common.ModelSelection(providerId = "zai-coding-cn", modelId = "glm-5.3"),
            variant = "high",
        )
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/session.selectModel", "/api/session.prompt"), paths)
        val payload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).first())).jsonObject["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("zai-coding-cn", payload["provider"]!!.jsonPrimitive.content)
        assertEquals("glm-5.3", payload["model"]!!.jsonPrimitive.content)
        assertEquals("high", payload["reasoningEffort"]!!.jsonPrimitive.content)
    }

    /** variant=null（默认档）→ reasoningEffort 键缺席（服务器侧用 defaultEffort）。 */
    @Test
    fun `promptAsync omits reasoningEffort when variant null`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session.selectModel" -> respond(ok("""{"selected":{"provider":"p","model":"m"}}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        client(engine).promptAsync(
            conn, "s-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "hi")),
            model = dev.leonardo.ocbeacon.data.dto.common.ModelSelection(providerId = "p", modelId = "m"),
        )
        val payload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).first())).jsonObject["payload"]!!.jsonObject
        assertNull(payload["reasoningEffort"])
    }

    /** model=null → 不发 selectModel（无选择不强切，零额外往返）。 */
    @Test
    fun `promptAsync skips selectModel when model null`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        client(engine).promptAsync(
            conn, "s-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "hi")),
        )
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/session.prompt"), paths)
    }

    /**
     * agent-busy 容错（11 号实测证据：subagent-origin 会话 selectModel 被拒
     * agent-busy）——拒绝不阻断发送：prompt 照发、不抛异常。
     */
    @Test
    fun `promptAsync continues prompt when selectModel rejected agent-busy`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session.selectModel" -> respond(err("agent-busy", "session is owned by subagent routing"), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val admission = client(engine).promptAsync(
            conn, "s-1",
            listOf(dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "hello")),
            model = dev.leonardo.ocbeacon.data.dto.common.ModelSelection(providerId = "p", modelId = "m"),
        )
        assertNull(admission) // 未因 selectModel 拒绝而失败
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/session.selectModel", "/api/session.prompt"), paths)
    }

    private fun jsonHeaders() = headersOf("Content-Type" to listOf("application/json"))

    // ============ DSH goal 六 mutation + commands/list（backlog #286） ============

    @Test
    fun `goalCreate posts goal dot create with objective and optional maxGoalRounds`() = runTest {
        val engine = MockEngine { respond(ok("""{"ref":{"id":"goal-1","revision":1}}"""), HttpStatusCode.OK, jsonHeaders()) }
        val ref = client(engine).goalCreate(conn, "s-9", "build the ring", 5)
        assertEquals("goal-1", ref!!.id)
        assertEquals(1L, ref.revision)
        val req = captureRequests(engine).single()
        assertEquals("/api/goal.create", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        val payload = body["payload"]!!.jsonObject
        assertEquals("s-9", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("build the ring", payload["objective"]!!.jsonPrimitive.content)
        assertEquals(5, payload["maxGoalRounds"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `goalEdit posts ref CAS and optional fields`() = runTest {
        val engine = MockEngine { respond(ok("""{"ref":{"id":"goal-1","revision":2}}"""), HttpStatusCode.OK, jsonHeaders()) }
        val ref = client(engine).goalEdit(conn, "s-9", DshGoalRef("goal-1", 1L), objective = "v2", maxGoalRounds = null)
        assertEquals(2L, ref!!.revision)
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        val payload = body["payload"]!!.jsonObject
        assertEquals("goal-1", payload["ref"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, payload["ref"]!!.jsonObject["revision"]!!.jsonPrimitive.content.toInt())
        assertEquals("v2", payload["objective"]!!.jsonPrimitive.content)
        assertNull(payload["maxGoalRounds"])
    }

    @Test
    fun `goalPause resume complete share ref mutation shape`() = runTest {
        val ref = DshGoalRef("goal-1", 1L)
        for (method in listOf("goal.pause", "goal.resume", "goal.complete")) {
            val engine = MockEngine { respond(ok("""{"ref":{"id":"goal-1","revision":3}}"""), HttpStatusCode.OK, jsonHeaders()) }
            val out = when (method) {
                "goal.pause" -> client(engine).goalPause(conn, "s-9", ref)
                "goal.resume" -> client(engine).goalResume(conn, "s-9", ref)
                else -> client(engine).goalComplete(conn, "s-9", ref)
            }
            assertEquals(3L, out!!.revision)
            val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
            assertEquals(method, body["method"]!!.jsonPrimitive.content)
            assertEquals("/api/" + method, captureRequests(engine).single().url.encodedPath)
        }
    }

    @Test
    fun `goalClear returns cleared flag`() = runTest {
        val engine = MockEngine { respond(ok("""{"cleared":true}"""), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).goalClear(conn, "s-9", DshGoalRef("goal-1", 1L)))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("goal.clear", body["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listCommands maps descriptor array via commands list typert channel`() = runTest {
        val value = """[{"name":"compact","description":"Compact older conversation history"},
{"name":"goal","description":"set or view the goal for a long-running task","input":{"hint":"[<objective>|clear|edit <objective>|pause|resume]","images":true}},
{"name":"permission","description":"Switch the permission preset","input":{"hint":"<preset>"}}]"""
        val engine = MockEngine { respond(ok(value), HttpStatusCode.OK, jsonHeaders()) }
        val commands = client(engine).listCommands(conn, "s-9")
        assertEquals(3, commands.size)
        assertEquals("compact", commands[0].name)
        assertEquals("[<objective>|clear|edit <objective>|pause|resume]", commands[1].hints.single())
        assertEquals("server", commands[1].source)
        val req = captureRequests(engine).single()
        assertEquals("/api/commands/list", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("s-9", body["payload"]!!.jsonObject["args"]!!.jsonObject["agentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listCommands without session returns empty without request`() = runTest {
        val engine = MockEngine { respond(ok("[]"), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine).listCommands(conn, null).isEmpty())
        assertTrue(engine.requestHistory.isEmpty())
    }

    // ============ #318：0.1.2 方法面语义分支（V012 payload 形态断言） ============

    /** V012 session/list：斜杠方法 + {args:{_request:{}}}（WRAPPED 专用键）。 */
    @Test
    fun `v012 listSessions posts slash session list with _request args`() = runTest {
        val engine = MockEngine { respond(ok(sessionListValue), HttpStatusCode.OK, jsonHeaders()) }
        val sessions = client(engine, DshWireProtocol.V012).listSessions(conn)
        assertEquals(listOf("s-1", "s-2"), sessions.map { it.id })
        val req = captureRequests(engine).single()
        assertEquals("/api/session/list", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session/list", body["method"]!!.jsonPrimitive.content)
        assertEquals("""{"args":{"_request":{}}}""", body["payload"].toString())
    }

    /**
     * V012 create：schema 无 title/parentSessionId——payload 只放 cwd；title 非空
     * 时创建成功后追加 session/rename（失败仅告警）。两步载荷均 {args:{request:…}}。
     */
    @Test
    fun `v012 createSession sends cwd-only create then rename when title present`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/create" -> respond(ok("""{"sessionId":"session-new-2","agentPreset":"code"}"""), HttpStatusCode.OK, jsonHeaders())
                "/api/session/rename" -> respond(ok("""{"sessionId":"session-new-2"}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val session = client(engine, DshWireProtocol.V012)
            .createSession(conn, title = "My Title", parentId = "p-1", directory = "/tmp")
        assertEquals("session-new-2", session.id)
        assertEquals("My Title", session.title)
        assertTrue(session.blank)
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/session/create", "/api/session/rename"), paths)
        val create = json.parseToJsonElement(bodyTextOf(captureRequests(engine)[0])).jsonObject
        assertEquals("session/create", create["method"]!!.jsonPrimitive.content)
        assertEquals("""{"args":{"request":{"cwd":"/tmp"}}}""", create["payload"].toString())
        val rename = json.parseToJsonElement(bodyTextOf(captureRequests(engine)[1])).jsonObject
        assertEquals("session/rename", rename["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"request":{"sessionId":"session-new-2","title":"My Title"}}}""",
            rename["payload"].toString(),
        )
    }

    /** V012 title 空——单发 create，无 rename 追加。 */
    @Test
    fun `v012 createSession without title issues single create without rename`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-new-3"}"""), HttpStatusCode.OK, jsonHeaders()) }
        val session = client(engine, DshWireProtocol.V012)
            .createSession(conn, title = null, parentId = null, directory = "/tmp")
        assertEquals("session-new-3", session.id)
        assertEquals(1, captureRequests(engine).size)
        val create = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("""{"args":{"request":{"cwd":"/tmp"}}}""", create["payload"].toString())
    }

    /**
     * #308 回修(2026-09-04 真机定音):V012 审批 waterfall 应答的 value 必须是
     * 裸字符串 allowed-once/rejected——dsh-user-approval decide() 以
     * OUTCOMES.includes(outcome) 归一化,对象形态 {outcome:...} 恒判 unavailable
     * (fail-closed,代理侧表现 no approval channel available)。网关对
     * eventsResult 恒回 ok:true(pending 缺失也静默丢弃),旧对象形态在
     * App 侧恒成功——假阳性根因。本测断言 value 为 JsonPrimitive。
     */
    @Test
    fun `v012 replyToPermission eventsResult value is bare outcome string`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":null}}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val registry = mockk<DshConnectionRegistry>(relaxed = true)
        every { registry.protocolOf(any()) } returns DshWireProtocol.V012
        every { registry.cookieHeader(any()) } returns null
        every { registry.clientId(any()) } returns "client-1"
        val c = DshApiClient(
            DshRpcClient(ApiClient(HttpClient(engine), json), registry),
            FixedProtocolSource(DshWireProtocol.V012),
        )
        assertTrue(c.replyToPermission(conn, "ses-1", "appr-9", "once", metadata = mapOf("rpcId" to "wf-7")))
        val req = captureRequests(engine).single()
        assertEquals("/api/\$events/result", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        val args = body["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("wf-7", args["eventId"]!!.jsonPrimitive.content)
        val outcome = args["outcome"]!!.jsonObject
        assertEquals("result", outcome["kind"]!!.jsonPrimitive.content)
        val valueEl = outcome["value"]
        assertTrue(valueEl is kotlinx.serialization.json.JsonPrimitive)
        assertEquals("allowed-once", (valueEl as kotlinx.serialization.json.JsonPrimitive).content)
    }

    /**
     * #328(2026-09-05 网关源码+真机定音):V012 提问拒绝 waterfall 的 error 必须
     * name 非空+message,可选 code/details——网关 parseRemoteEventRejection
     * (gateway index.js L158-159)对缺 name 的 error 抛 invalid Remote event
     * result → dispatchRpc rpcFailure → ok:false(app isSuccess=false,
     * #327 验收执行员真机实测)→ waterfall 永不解除 → 代理冻结在提问等待。
     * 正字法=web 端 questionError:{name:"UserQuestionError",code:"ASK_CANCELLED"}
     * (user-questions restoreUserQuestionError 按 name 复原类型,工具层收规范错误)。
     */
    @Test
    fun `v012 rejectQuestion error envelope carries name and code`() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":null}}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val registry = mockk<DshConnectionRegistry>(relaxed = true)
        every { registry.protocolOf(any()) } returns DshWireProtocol.V012
        every { registry.cookieHeader(any()) } returns null
        every { registry.clientId(any()) } returns "client-1"
        val c = DshApiClient(
            DshRpcClient(ApiClient(HttpClient(engine), json), registry),
            FixedProtocolSource(DshWireProtocol.V012),
        )
        assertTrue(c.rejectQuestion(conn, "frame-q", null, "ses-2"))
        val req = captureRequests(engine).single()
        assertEquals("/api/\$events/result", req.url.encodedPath)
        val args = json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("frame-q", args["eventId"]!!.jsonPrimitive.content)
        assertEquals("client-1", args["clientId"]!!.jsonPrimitive.content)
        val outcome = args["outcome"]!!.jsonObject
        assertEquals("rejected", outcome["kind"]!!.jsonPrimitive.content)
        val error = outcome["error"]!!.jsonObject
        assertEquals("UserQuestionError", error["name"]!!.jsonPrimitive.content)
        assertEquals("ASK_CANCELLED", error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    /** V012 rename 追加失败仅告警——会话创建结果不受影响(本地 title 回退保真)。 */
    @Test
    fun `v012 createSession tolerates rename failure with warning only`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/create" -> respond(ok("""{"sessionId":"s-new"}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(err("internal", "rename rejected"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val session = client(engine, DshWireProtocol.V012)
            .createSession(conn, title = "T", parentId = null, directory = null)
        assertEquals("s-new", session.id)
        assertEquals("T", session.title)
    }

    /**
     * V012 history→page：方法名仍传 session.history（adapter 翻 session/page）；
     * 载荷 {address:{kind:session,sessionId},throughSeq,…}——throughSeq 先行
     * session/list 读 projections.asOfSeq；响应行数组键 records；{type:chunks}
     * 压缩行跳过（fold 不认识会 refusedRebuild）。
     */
    @Test
    fun `v012 listMessages resolves throughSeq then posts page address and skips chunk rows`() = runTest {
        val pageValue = """{"records":[
            {"type":"event","event":{"type":"user/message","seq":5,"time":11,"data":{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}}},
            {"type":"chunks","event":{"type":"chunkrow/begin","seq":6,"time":12}}
        ],"hasMore":false}""".trimIndent()
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/list" -> respond(
                    ok("""{"items":[{"sessionId":"s-1","cwd":"/w","projections":{"asOfSeq":42,"values":{}}}]}"""),
                    HttpStatusCode.OK, jsonHeaders(),
                )
                "/api/session/page" -> respond(ok(pageValue), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val page = client(engine, DshWireProtocol.V012).listMessages(conn, "s-1", limit = 30, before = null)
        assertEquals(1, page.messages.size) // chunks 行跳过
        assertEquals("seq-5", page.messages[0].info.id)
        assertNull(page.nextCursor) // hasMore=false
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/session/list", "/api/session/page"), paths)
        val pageReq = json.parseToJsonElement(bodyTextOf(captureRequests(engine)[1])).jsonObject
        assertEquals("session/page", pageReq["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"request":{"address":{"kind":"session","sessionId":"s-1"},"throughSeq":42,"maxMessages":30}}}""",
            pageReq["payload"].toString(),
        )
    }
    /**
     * #310① A8 缺陷A根因钉：origin=subagent 子会话的 page/follow 恒 {kind:session}
     * 地址被服务器拒（"subagent Sessions require their durable parent address"——
     * A8 logcat 5454：session.history failed for 4ca8416a，子会话转录 3min 恒空的
     * history 腿）。V012 须按 session.list 行装配 {kind:subagent,parentSessionId,
     * childSessionId,mode} durable 地址（mode 与 subagent 投影身份严格一致——
     * 服务器 validateAddress 强校验）。
     */
    @Test
    fun `v012 listMessages subagent child pages via durable subagent address`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/list" -> respond(
                    ok(
                        """{"items":[{"sessionId":"s-child","cwd":"/w","parentSessionId":"s-parent","origin":"subagent","projections":{"asOfSeq":9,"values":{"subagent":{"mode":"continuable","label":"counter"}}}}]}""",
                    ),
                    HttpStatusCode.OK, jsonHeaders(),
                )
                "/api/session/page" -> respond(ok("""{"records":[],"hasMore":false}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        client(engine, DshWireProtocol.V012).listMessages(conn, "s-child", limit = 30, before = null)
        val pageReq = json.parseToJsonElement(bodyTextOf(captureRequests(engine)[1])).jsonObject
        assertEquals(
            """{"args":{"request":{"address":{"kind":"subagent","parentSessionId":"s-parent","childSessionId":"s-child","mode":"continuable"},"throughSeq":9,"maxMessages":30}}}""",
            pageReq["payload"].toString(),
        )
    }

    /** V012 throughSeq 解析：session.list 无该会话条目 → IllegalStateException。 */
    @Test
    fun `v012 listMessages throws when session absent from list`() = runTest {
        val engine = MockEngine { respond(ok("""{"items":[{"sessionId":"other","cwd":"/w"}]}"""), HttpStatusCode.OK, jsonHeaders()) }
        val outcome = runCatching { client(engine, DshWireProtocol.V012).listMessages(conn, "s-missing") }
        assertTrue(outcome.exceptionOrNull() is IllegalStateException)
    }

    /**
     * V012 prompt：requestId 必填（UUID 字符串）；图片 part 字段 mediaType
     * （0.1.1 是 mime）。载荷 {args:{request:{requestId,sessionId,content,mode}}}。
     */
    @Test
    fun `v012 promptAsync adds requestId and image mediaType`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        client(engine, DshWireProtocol.V012).promptAsync(
            conn, "s-1",
            listOf(
                dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "file", url = "data:image/png;base64,aGk=", mime = "image/png"),
                dev.leonardo.ocbeacon.data.dto.request.PromptPart(type = "text", text = "hello"),
            ),
        )
        val req = captureRequests(engine).single()
        assertEquals("/api/session/prompt", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session/prompt", body["method"]!!.jsonPrimitive.content)
        val request = body["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("s-1", request["sessionId"]!!.jsonPrimitive.content)
        assertEquals("queue", request["mode"]!!.jsonPrimitive.content)
        val requestId = request["requestId"]!!.jsonPrimitive.content
        assertEquals(36, requestId.length)
        assertTrue("requestId 必须可解析为 UUID", runCatching { java.util.UUID.fromString(requestId) }.isSuccess)
        val image = request["content"]!!.jsonArray[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("aGk=", image["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", image["mediaType"]!!.jsonPrimitive.content)
        assertNull(image["mime"])
    }

    /** V012 goals/create SELF：调用点全量构造 {args:{agentId,request:…}}。 */
    @Test
    fun `v012 goalCreate wraps args with agentId and request`() = runTest {
        val engine = MockEngine { respond(ok("""{"ref":{"id":"goal-9","revision":1}}"""), HttpStatusCode.OK, jsonHeaders()) }
        val ref = client(engine, DshWireProtocol.V012).goalCreate(conn, "s-9", "build the ring", 5)
        assertEquals("goal-9", ref!!.id)
        val req = captureRequests(engine).single()
        assertEquals("/api/goals/create", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("goals/create", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-9","request":{"objective":"build the ring","maxGoalRounds":5}}}""",
            body["payload"].toString(),
        )
    }

    /** V012 goals/edit SELF：{args:{agentId,ref,request}}（request 内字段条件放入）。 */
    @Test
    fun `v012 goalEdit wraps args with ref and request`() = runTest {
        val engine = MockEngine { respond(ok("""{"ref":{"id":"goal-9","revision":2}}"""), HttpStatusCode.OK, jsonHeaders()) }
        val ref = client(engine, DshWireProtocol.V012)
            .goalEdit(conn, "s-9", DshGoalRef("goal-9", 1L), objective = "v2", maxGoalRounds = null)
        assertEquals(2L, ref!!.revision)
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("goals/edit", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-9","ref":{"id":"goal-9","revision":1},"request":{"objective":"v2"}}}""",
            body["payload"].toString(),
        )
    }

    /** V012 pause/resume/complete 共形 {args:{agentId,ref}}（FLAT 不进——SELF 语义构造）。 */
    @Test
    fun `v012 ref mutations post agentId and ref args`() = runTest {
        val engine = MockEngine { respond(ok("""{"ref":{"id":"goal-1","revision":3}}"""), HttpStatusCode.OK, jsonHeaders()) }
        client(engine, DshWireProtocol.V012).goalPause(conn, "s-9", DshGoalRef("goal-1", 2L))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("goals/pause", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-9","ref":{"id":"goal-1","revision":2}}}""",
            body["payload"].toString(),
        )
    }

    /** V012 goals/clear 回执是 GoalRef 本身（顶层 {id,revision}）——成功即 true。 */
    @Test
    fun `v012 goalClear returns success on GoalRef receipt`() = runTest {
        val engine = MockEngine { respond(ok("""{"id":"goal-1","revision":4}"""), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine, DshWireProtocol.V012).goalClear(conn, "s-9", DshGoalRef("goal-1", 3L)))
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("goals/clear", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-9","ref":{"id":"goal-1","revision":3}}}""",
            body["payload"].toString(),
        )
    }

    /**
     * V012 agentPresets/select：回程 value 是字符串（"standard"）非对象——走
     * callJson 面；载荷由 adapter FLAT 自动改名（sessionId→agentId）+ 包装。
     */
    @Test
    fun `v012 selectAgentPreset tolerates string value and renames sessionId`() = runTest {
        val engine = MockEngine { respond(ok("\"standard\""), HttpStatusCode.OK, jsonHeaders()) }
        assertTrue(client(engine, DshWireProtocol.V012).selectAgentPreset(conn, "s-1", "standard"))
        val req = captureRequests(engine).single()
        assertEquals("/api/agentPresets/select", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("agentPresets/select", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"agentId":"s-1","agentPreset":"standard"}}""",
            body["payload"].toString(),
        )
    }

    /** V012 subagents/list：FLAT 平铺 parentSessionId（无 request 包装、无改名）。 */
    @Test
    fun `v012 listSubagentCatalog posts subagents list with flat parentSessionId`() = runTest {
        val engine = MockEngine { respond(ok(subagentCatalogValue), HttpStatusCode.OK, jsonHeaders()) }
        val entries = client(engine, DshWireProtocol.V012).listSubagentCatalog(conn, "root-1")
        assertEquals(3, entries.size)
        val body = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject
        assertEquals("subagents/list", body["method"]!!.jsonPrimitive.content)
        assertEquals("""{"args":{"parentSessionId":"root-1"}}""", body["payload"].toString())
    }

    /** V012 host.describe 已删——getHealth 直接返回常量健康（不发 RPC）。 */
    @Test
    fun `v012 getHealth returns constant health without rpc`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        val health = client(engine, DshWireProtocol.V012).getHealth(conn)
        assertTrue(health.healthy)
        assertEquals("0.1.2", health.version)
        assertTrue(engine.requestHistory.isEmpty())
    }

    /** V012 getServerPaths：session/list 首条 cwd 兜底（无 host.describe）。 */
    @Test
    fun `v012 getServerPaths falls back to first session cwd`() = runTest {
        val engine = MockEngine { respond(ok(sessionListValue), HttpStatusCode.OK, jsonHeaders()) }
        val paths = client(engine, DshWireProtocol.V012).getServerPaths(conn)
        assertEquals("/w/one", paths.directory)
        assertEquals("/api/session/list", captureRequests(engine).single().url.encodedPath)
    }

    /** V012 workspace.list 无对应——session.list 聚合 distinct cwd 构造 Project。 */
    @Test
    fun `v012 listProjects aggregates distinct session cwds`() = runTest {
        val engine = MockEngine { respond(ok(sessionListValue), HttpStatusCode.OK, jsonHeaders()) }
        val projects = client(engine, DshWireProtocol.V012).listProjects(conn)
        assertEquals(listOf("/w/one", "/w/two"), projects.map { it.id })
        assertEquals("/w/one", projects[0].worktree)
        assertEquals("one", projects[0].name) // name = 最后一段路径
        assertEquals("/api/session/list", captureRequests(engine).single().url.encodedPath)
    }

    /**
     * V012 空 path 根解析：跳过 workspace.list（404），直接 session.list 首条 cwd；
     * host.listDirectory → directoryPicker/list（FLAT {args:{path}}）。
     */
    @Test
    fun `v012 listDirectory resolves blank path via session list and posts directoryPicker`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/session/list" -> respond(
                    ok("""{"items":[{"sessionId":"s-1","cwd":"/w/root"}]}"""),
                    HttpStatusCode.OK, jsonHeaders(),
                )
                else -> respond(ok("""{"entries":[{"name":"src","type":"directory"}]}"""), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val nodes = client(engine, DshWireProtocol.V012).listDirectory(conn, path = "")
        assertEquals(listOf("src"), nodes.map { it.name })
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/session/list", "/api/directoryPicker/list"), paths)
        val listPayload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).last()))
            .jsonObject["payload"]!!.jsonObject
        assertEquals("""{"args":{"path":"/w/root"}}""", listPayload.toString())
    }

    /**
     * V012 目录：llm.providers→llm/listProviders 回**数组** [{id,name}]（callJson
     * 面）；llm.models→session/modelCatalog **groups 数组** [{id,name,models[]}]
     * （生产 0.1.2-rc.1 实测形态，2026-09-04 智谱模型缺席定因；顶层另含
     * default/routableProviders/failures）——合流语义与 V011 同构（目录名优先、
     * 未知组追加）。
     */
    @Test
    fun `v012 getProviders parses provider array and model catalog keys`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/llm/listProviders" -> respond(
                    ok("""[{"id":"deepseek-official","name":"DeepSeek"},{"id":"opencode-go","name":"opencode-go"}]"""),
                    HttpStatusCode.OK, jsonHeaders(),
                )
                "/api/session/modelCatalog" -> respond(
                    ok(
                        """{"default":"deepseek-official","routableProviders":["deepseek-official","opencode-go","zai-coding-cn"],""" +
                            """"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"deepseek-v4-flash","name":"DeepSeek-V4-Flash","reasoning":{"efforts":[{"id":"high","name":"High"}]}}]},""" +
                            """{"id":"zai-coding-cn","name":"智谱","models":[{"id":"glm-5.3","name":"GLM-5.3","reasoning":{"efforts":[{"id":"low"},{"id":"max"}],"defaultEffort":"max"}},{"id":"glm-5.3-flash"}]}],""" +
                            """"failures":{}}""",
                    ),
                    HttpStatusCode.OK, jsonHeaders(),
                )
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val response = client(engine, DshWireProtocol.V012).getProviders(conn)
        assertEquals(listOf("deepseek-official", "opencode-go", "zai-coding-cn"), response.providers.map { it.id })
        assertEquals("DeepSeek", response.providers[0].name) // 目录名优先于组名
        val flash = response.providers[0].models["deepseek-v4-flash"]
        assertNotNull(flash)
        assertEquals(listOf("high"), flash!!.variants?.keys?.toList())
        // 用户反馈回归锚：智谱组（groups 数组内）缺席 = 切换模型无智谱——必须解析出
        val glm = response.providers[2].models["glm-5.3"]
        assertNotNull(glm)
        assertEquals("GLM-5.3", glm!!.name)
        assertEquals(listOf("low", "max"), glm.variants?.keys?.toList())
        assertEquals("glm-5.3-flash", response.providers[2].models["glm-5.3-flash"]?.id)
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/llm/listProviders", "/api/session/modelCatalog"), paths)
        // 两端点 0.1.2 无参——payload 恒 {args:{}}（EMPTY_ARGS）
        captureRequests(engine).forEach { req ->
            assertEquals("""{"args":{}}""", json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"].toString())
        }
    }

    /** V012 目录解析不出组（无 models 数组键）→ 退化为只有 providers 目录。 */
    @Test
    fun `v012 getProviders degrades to providers-only when catalog unparsable`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/llm/listProviders" -> respond(ok("""[{"id":"p-1","name":"P1"}]"""), HttpStatusCode.OK, jsonHeaders())
                "/api/session/modelCatalog" -> respond(ok("""{"default":"p-1","unrelated":"x"}"""), HttpStatusCode.OK, jsonHeaders())
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val response = client(engine, DshWireProtocol.V012).getProviders(conn)
        assertEquals(listOf("p-1"), response.providers.map { it.id })
        assertTrue(response.providers.all { it.models.isEmpty() })
    }

    /** V012 fallback：groups 数组缺席时按顶层键为组解析（防御 alpha.5 形态）。 */
    @Test
    fun `v012 getProviders falls back to top-level group keys without groups array`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/llm/listProviders" -> respond(ok("""[{"id":"p-1","name":"P1"}]"""), HttpStatusCode.OK, jsonHeaders())
                "/api/session/modelCatalog" -> respond(
                    ok("""{"default":"p-1","p-1":{"models":[{"id":"m-1","name":"M1"}]}}"""),
                    HttpStatusCode.OK, jsonHeaders(),
                )
                else -> respond(ok("{}"), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val response = client(engine, DshWireProtocol.V012).getProviders(conn)
        assertEquals(listOf("p-1"), response.providers.map { it.id })
        assertEquals("M1", response.providers[0].models["m-1"]?.name)
    }

    /**
     * V012 settings 域：describe 无参（{args:{}}）；mutate FLAT 平铺
     * {args:{ns,ops,expectedRevision}}——expectedRevision 恒非 JsonNull（双保险）。
     */
    @Test
    fun `v012 settings describe empty args and mutate flat args with expectedRevision`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/settings/describe" -> respond(ok(settingsDescribeValue), HttpStatusCode.OK, jsonHeaders())
                else -> respond(
                    ok("""{"ns":"permission","value":{"defaultPreset":"read-only"},"revision":43}"""),
                    HttpStatusCode.OK, jsonHeaders(),
                )
            }
        }
        assertTrue(client(engine, DshWireProtocol.V012).setPermissionDefault(conn, "read-only"))
        val paths = captureRequests(engine).map { it.url.encodedPath }
        assertEquals(listOf("/api/settings/describe", "/api/settings/mutate"), paths)
        val describe = json.parseToJsonElement(bodyTextOf(captureRequests(engine)[0])).jsonObject
        assertEquals("settings/describe", describe["method"]!!.jsonPrimitive.content)
        assertEquals("""{"args":{}}""", describe["payload"].toString())
        val mutate = json.parseToJsonElement(bodyTextOf(captureRequests(engine)[1])).jsonObject
        assertEquals("settings/mutate", mutate["method"]!!.jsonPrimitive.content)
        val args = mutate["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("permission", args["ns"]!!.jsonPrimitive.content)
        assertEquals(42L, args["expectedRevision"]!!.jsonPrimitive.content.toLong())
        val op = args["ops"]!!.jsonArray[0].jsonObject
        assertEquals("set", op["op"]!!.jsonPrimitive.content)
        assertEquals("defaultPreset", op["path"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("read-only", op["value"]!!.jsonPrimitive.content)
    }

    /** V012 导出（非信封 GET）：0.1.2 鉴权栅栏——Cookie 头挂载（URL 不变）。 */
    @Test
    fun `v012 exportSessionToStream attaches cookie header`() = runTest {
        val zipBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val engine = MockEngine {
            respond(zipBytes, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/zip")))
        }
        client(engine, DshWireProtocol.V012, cookie = "dsh-auth-x=v1.abc")
            .exportSessionToStream(conn, "s-1", java.io.ByteArrayOutputStream()) {}
        val req = captureRequests(engine).single()
        // 非信封 GET 入口——URL 不变（/api/session.export 点式路径，非方法名形态）
        assertEquals("/api/session.export", req.url.encodedPath)
        assertEquals("dsh-auth-x=v1.abc", req.headers["Cookie"])
    }

    // ============ #311 Task1：workspace 数据层（契约 2026-09-05-311-wire-contracts ①-a/①-d） ============

    /**
     * V012 workspace/archiveSession（#310 wire 契约钉死同款）：URL/信封方法名
     * workspace/archiveSession；载荷 {args:{request:{sessionId}}}（typert 参数
     * wire:'request'——WRAPPED 缺省键）；回执 {archivedSessionIds} 是**完整新
     * 集合**（集合替换式，非增量合并）。
     */
    @Test
    fun `v012 archiveSession posts workspace archiveSession and parses receipt`() = runTest {
        val engine = MockEngine { respond(ok("""{"archivedSessionIds":["s-2","s-9"]}"""), HttpStatusCode.OK, jsonHeaders()) }
        val archived = client(engine, DshWireProtocol.V012).archiveSession(conn, "s-9")
        assertEquals(listOf("s-2", "s-9"), archived)
        val req = captureRequests(engine).single()
        assertEquals("/api/workspace/archiveSession", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("workspace/archiveSession", body["method"]!!.jsonPrimitive.content)
        assertEquals(
            """{"args":{"request":{"sessionId":"s-9"}}}""",
            body["payload"].toString(),
        )
    }

    /** V011 线面无 workspace/archiveSession（0.1.1 方法面无此动词）→ UnsupportedServerCapability。 */
    @Test
    fun `archiveSession throws unsupported on v011`() = runTest {
        val engine = MockEngine { respond(ok("{}"), HttpStatusCode.OK, jsonHeaders()) }
        val outcome = runCatching { client(engine).archiveSession(conn, "s-1") }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability)
    }

    /** workspace.list 完整映射（契约 ①-d WorkspaceView：名字键=title、归属=sessionIds）。 */
    @Test
    fun `listWorkspaces maps full workspace view shape`() = runTest {
        val engine = MockEngine {
            respond(
                ok(
                    """{"items":[
                        {"workspaceId":"ws-1","path":"/home/leo/proj","title":"Beacon",
                         "sessionIds":["s-1","s-2"],
                         "createdAt":"2026-09-05T00:00:00Z","updatedAt":"2026-09-05T01:00:00Z"},
                        {"workspaceId":"ws-2","path":"/srv/ops","title":"Ops","sessionIds":[]}
                    ]}""",
                ),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val workspaces = client(engine).listWorkspaces(conn)
        assertEquals(2, workspaces.size)
        assertEquals("ws-1", workspaces[0].workspaceId)
        assertEquals("/home/leo/proj", workspaces[0].path)
        assertEquals("Beacon", workspaces[0].title)
        assertEquals(listOf("s-1", "s-2"), workspaces[0].sessionIds)
        assertTrue(workspaces[1].sessionIds.isEmpty())
    }

    /** 旧线面形状回退（0.1.1 {id,name}）：id→workspaceId、name→title（零回归兼容）。 */
    @Test
    fun `listWorkspaces falls back to legacy id and name keys`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"items":[{"id":"ws-old","path":"/w/one","name":"Legacy"}]}"""), HttpStatusCode.OK, jsonHeaders())
        }
        val workspaces = client(engine).listWorkspaces(conn)
        assertEquals(1, workspaces.size)
        assertEquals("ws-old", workspaces[0].workspaceId)
        assertEquals("Legacy", workspaces[0].title)
        assertTrue(workspaces[0].sessionIds.isEmpty())
    }

    /** title 缺席 → basename(path)（服务器 create 默认语义——名字键永不空）。 */
    @Test
    fun `listWorkspaces defaults title to path basename`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"items":[{"workspaceId":"ws-3","path":"/w/deep/proj"}]}"""), HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals("proj", client(engine).listWorkspaces(conn).single().title)
    }

    /** V012 create 带 workspaceId（契约 ①-d SessionCreateRequest.workspaceId）。 */
    @Test
    fun `v012 createSession includes workspaceId when provided`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-ws-1"}"""), HttpStatusCode.OK, jsonHeaders()) }
        client(engine, DshWireProtocol.V012)
            .createSession(conn, title = null, parentId = null, directory = null, workspaceId = "ws-1")
        val req = captureRequests(engine).single()
        val body = json.parseToJsonElement(bodyTextOf(req)).jsonObject
        assertEquals("session/create", body["method"]!!.jsonPrimitive.content)
        val request = body["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("ws-1", request["workspaceId"]!!.jsonPrimitive.content)
    }

    /** workspaceId 默认 null → 载荷不放键（既有调用方零回归）。 */
    @Test
    fun `v012 createSession omits workspaceId by default`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-new-4"}"""), HttpStatusCode.OK, jsonHeaders()) }
        client(engine, DshWireProtocol.V012).createSession(conn, title = null, parentId = null, directory = "/tmp")
        val req = captureRequests(engine).single()
        val request = json.parseToJsonElement(bodyTextOf(req)).jsonObject
            .get("payload")!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertFalse(request.containsKey("workspaceId"))
        assertEquals("/tmp", request["cwd"]!!.jsonPrimitive.content)
    }

    // ============ #312⑤ fork 锚点（session.fork atSeq 上 wire）============

    /** 服务器契约：SessionForkRequest { sessionId, atSeq? }——atSeq 为事件
     * seq 锚点（fork 到包含该事件的已完成轮次边界；dsh-api-session-controller
     * types.ts SessionForkRequest）。DSH 会话的消息 id 即 "seq-{seq}"（DshEventMapper
     * messageId 契约）——轮尾锚点入口传入后反解上 wire。 */
    @Test
    fun `forkSession sends atSeq for seq anchor messageId`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-fork-1"}"""), HttpStatusCode.OK, jsonHeaders()) }
        val session = client(engine).forkSession(conn, "s-1", "seq-42")
        assertEquals("session-fork-1", session.id)
        val req = captureRequests(engine).single()
        assertEquals("/api/session.fork", req.url.encodedPath)
        val payload = json.parseToJsonElement(bodyTextOf(req)).jsonObject["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals(42L, payload["atSeq"]!!.jsonPrimitive.long)
    }

    /** 无锚点（null）→ 载荷仅 sessionId（既有行为：fork 到最后完成轮次）。 */
    @Test
    fun `forkSession omits atSeq without messageId`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-fork-2"}"""), HttpStatusCode.OK, jsonHeaders()) }
        client(engine).forkSession(conn, "s-1", null)
        val payload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("atSeq"))
    }

    // ---- #331 回执时间戳：create/fork 回显无 updatedAt（0.1.2 schema 只有
    // {sessionId, agentPreset?}/{sessionId}）→ 本地时钟补 updated（排序位），
    // 防 epoch0 沉列表底部。

    @Test
    fun `v012 createSession echo without updatedAt stamps local clock`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"s-new"}"""), HttpStatusCode.OK, jsonHeaders()) }
        val api = client(engine, DshWireProtocol.V012)
        api.echoClock = { 1_788_626_112_891L }
        val session = api.createSession(conn, title = null, parentId = null, directory = "/tmp")
        assertEquals(1_788_626_112_891L, session.time.updated)
    }

    @Test
    fun `v012 forkSession echo without updatedAt stamps local clock`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-fork-9"}"""), HttpStatusCode.OK, jsonHeaders()) }
        val api = client(engine)
        api.echoClock = { 1_788_626_112_892L }
        val session = api.forkSession(conn, "s-1", null)
        assertEquals("session-fork-9", session.id)
        assertEquals(1_788_626_112_892L, session.time.updated)
    }

    /** 回显带 updatedAt（未来 0.1.2+ 或 V011 形态）→ 采真值不覆盖。 */
    @Test
    fun `echo with real updatedAt keeps wire value`() = runTest {
        val engine = MockEngine {
            respond(ok("""{"sessionId":"s-real","updatedAt":42}"""), HttpStatusCode.OK, jsonHeaders())
        }
        val api = client(engine)
        api.echoClock = { 9_999L }
        val session = api.forkSession(conn, "s-1", null)
        assertEquals(42L, session.time.updated)
    }

    /** 非 seq 形态 id（V2 msg_* 等）→ 不上 atSeq（安全降级为无锚点 fork，不发噬变量）。 */
    @Test
    fun `forkSession omits atSeq for non-seq messageId`() = runTest {
        val engine = MockEngine { respond(ok("""{"sessionId":"session-fork-3"}"""), HttpStatusCode.OK, jsonHeaders()) }
        client(engine).forkSession(conn, "s-1", "msg_abc")
        val payload = json.parseToJsonElement(bodyTextOf(captureRequests(engine).single())).jsonObject["payload"]!!.jsonObject
        assertEquals("s-1", payload["sessionId"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("atSeq"))
    }
}
