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
}
