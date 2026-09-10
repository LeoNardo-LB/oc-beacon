package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import dev.leonardo.ocbeacon.domain.model.SseEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1/V2 方言路由契约（2026-08-26 架构走查 C1 批次）：同 fixture 参数下，
 * 域 Impl 必须按 conn.apiVersion 把调用单点路由到对应 client（pick），
 * 不允许串台（V1 连接的调用落 V2 或反之）。
 *
 * 背景：SessionApiImpl/MessageApiImpl 原 35 处逐方法 if (isV2) 分发收缩为
 * pick(conn) + 委托后，路由正确性由此测试守护。
 */
class V1V2DialectContractTest {

    private val v1 = mockk<V1ApiClient>()
    private val v2 = mockk<V2ApiClient>()

    // #276：三分路由第三实现（DSH）
    private val dsh = mockk<dev.leonardo.ocbeacon.data.api.dsh.DshApiClient>()

    // #391 切片 1：门面路由改走适配器注册表（唯一 seam）
    private val registry = dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry(
        setOf(
            dev.leonardo.ocbeacon.data.adapter.OpenCodeServerAdapter(v1, v2),
            dev.leonardo.ocbeacon.data.adapter.DshServerAdapter(
                dsh,
                object : dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource {
                    override fun protocolOf(baseUrl: String): dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol? = null
                },
            ),
        )
    )

    private val connV1 = ServerConnection("http://srv", null, ApiVersion.V1)
    private val connV2 = ServerConnection("http://srv", null, ApiVersion.V2)
    private val connDsh = ServerConnection("http://srv", null, ApiVersion.V1, dev.leonardo.ocbeacon.domain.model.ServerType.Dsh)

    // ---------- Session ----------


    // #391 切片4：门面已删除，测试按连接解析端口（路由正确性断言不变）
    private fun session(conn: ServerConnection) = registry.ports(conn).session
    private fun message(conn: ServerConnection) = registry.ports(conn).message
    private fun system(conn: ServerConnection) = registry.ports(conn).system
    private fun file(conn: ServerConnection) = registry.ports(conn).requireFile(conn)
    private fun provider(conn: ServerConnection) = registry.ports(conn).requireProvider(conn)
    private fun terminal(conn: ServerConnection) = registry.ports(conn).requireTerminal(conn)
    private fun shell(conn: ServerConnection) = registry.ports(conn).requireShell(conn)

    @Test
    fun `session - V2 conn routes listSessions to v2 only`() = runTest {
        coEvery { v2.listSessions(connV2, null, null, null, 50) } returns emptyList()

        session(connV2).listSessions(connV2)

        coVerify(exactly = 1) { v2.listSessions(connV2, null, null, null, 50) }
        coVerify(exactly = 0) { v1.listSessions(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `session - V1 conn routes listSessions to v1 only`() = runTest {
        coEvery { v1.listSessions(connV1, null, null, null, 50) } returns emptyList()

        session(connV1).listSessions(connV1)

        coVerify(exactly = 1) { v1.listSessions(connV1, null, null, null, 50) }
        coVerify(exactly = 0) { v2.listSessions(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `session - V1 conn routes interruptSession to v1 only`() = runTest {
        coEvery { v1.interruptSession(connV1, "ses_1", null) } returns true

        assertTrue(session(connV1).interruptSession(connV1, "ses_1"))

        coVerify(exactly = 1) { v1.interruptSession(connV1, "ses_1", null) }
        coVerify(exactly = 0) { v2.interruptSession(any(), any(), any()) }
    }

    @Test
    fun `session - V1 conn degrades backgroundSession inside v1 client`() = runTest {
        coEvery { v1.backgroundSession(connV1, "ses_1") } returns false

        assertFalse(session(connV1).backgroundSession(connV1, "ses_1"))

        coVerify(exactly = 1) { v1.backgroundSession(connV1, "ses_1") }
        coVerify(exactly = 0) { v2.backgroundSession(any(), any()) }
    }

    @Test
    fun `session - V2 conn routes backgroundSession and activeSessions to v2`() = runTest {
        coEvery { v2.backgroundSession(connV2, "ses_1") } returns true
        coEvery { v2.activeSessions(connV2) } returns emptyMap()

        assertTrue(session(connV2).backgroundSession(connV2, "ses_1"))
        assertTrue(session(connV2).activeSessions(connV2).isEmpty())

        coVerify(exactly = 1) { v2.backgroundSession(connV2, "ses_1") }
        coVerify(exactly = 1) { v2.activeSessions(connV2) }
        coVerify(exactly = 0) { v1.backgroundSession(any(), any()) }
        coVerify(exactly = 0) { v1.activeSessions(any()) }
    }

    @Test
    fun `session - fetchSessionStatus routes by conn and keeps C8 error taxonomy`() = runTest {
        coEvery { v1.fetchSessionStatus(connV1, null) } returns Result.success(emptyMap())
        coEvery { v2.fetchSessionStatus(connV2, null) } returns Result.success(emptyMap())

        session(connV1).fetchSessionStatus(connV1)
        session(connV2).fetchSessionStatus(connV2)

        coVerify(exactly = 1) { v1.fetchSessionStatus(connV1, null) }
        coVerify(exactly = 1) { v2.fetchSessionStatus(connV2, null) }
        coVerify(exactly = 0) { v1.fetchSessionStatus(connV2, any()) }
        coVerify(exactly = 0) { v2.fetchSessionStatus(connV1, any()) }
    }

    // ---------- System ----------

    @Test
    fun `system - V2 conn routes getHealth to v2 only`() = runTest {
        coEvery { v2.getHealth(connV2) } returns ServerHealth(healthy = true, version = "v2")

        assertEquals("v2", system(connV2).getHealth(connV2).version)

        coVerify(exactly = 1) { v2.getHealth(connV2) }
        coVerify(exactly = 0) { v1.getHealth(any()) }
    }

    @Test
    fun `system - V1 conn routes getHealth to v1 only`() = runTest {
        coEvery { v1.getHealth(connV1) } returns ServerHealth(healthy = true, version = "v1")

        assertEquals("v1", system(connV1).getHealth(connV1).version)

        coVerify(exactly = 1) { v1.getHealth(connV1) }
        coVerify(exactly = 0) { v2.getHealth(any()) }
    }

    @Test
    fun `system - listSkills passes directory through by conn`() = runTest {
        coEvery { v2.listSkills(connV2, "/home") } returns emptyList()
        coEvery { v1.listSkills(connV1, "/home") } returns emptyList()

        system(connV2).listSkills(connV2, "/home")
        system(connV1).listSkills(connV1, "/home")

        coVerify(exactly = 1) { v2.listSkills(connV2, "/home") }
        coVerify(exactly = 1) { v1.listSkills(connV1, "/home") }
        coVerify(exactly = 0) { v1.listSkills(connV2, any()) }
        coVerify(exactly = 0) { v2.listSkills(connV1, any()) }
    }

    @Test
    fun `system - mcp status routes by conn`() = runTest {
        coEvery { v1.getMcpStatus(connV1) } returns emptyMap()
        coEvery { v2.getMcpStatus(connV2) } returns emptyMap()

        system(connV1).getMcpStatus(connV1)
        system(connV2).getMcpStatus(connV2)

        coVerify(exactly = 1) { v1.getMcpStatus(connV1) }
        coVerify(exactly = 1) { v2.getMcpStatus(connV2) }
    }

    // ---------- Terminal ----------

    @Test
    fun `terminal - V2 conn routes updatePtySize to v2 only`() = runTest {
        coEvery { v2.updatePtySize(connV2, "pty_1", 80, 24, null) } returns true

        assertTrue(terminal(connV2).updatePtySize(connV2, "pty_1", 80, 24))

        coVerify(exactly = 1) { v2.updatePtySize(connV2, "pty_1", 80, 24, null) }
        coVerify(exactly = 0) { v1.updatePtySize(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `terminal - V1 conn routes runShellCommand to v1 only`() = runTest {
        coEvery { v1.runShellCommand(connV1, "ses_1", "ls", "build", null, null) } returns true

        assertTrue(terminal(connV1).runShellCommand(connV1, "ses_1", "ls", "build"))

        coVerify(exactly = 1) { v1.runShellCommand(connV1, "ses_1", "ls", "build", null, null) }
        coVerify(exactly = 0) { v2.runShellCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `terminal - listPtyShells passes directory by conn`() = runTest {
        coEvery { v2.listPtyShells(connV2, "/home") } returns emptyList()
        coEvery { v1.listPtyShells(connV1, "/home") } returns emptyList()

        terminal(connV2).listPtyShells(connV2, "/home")
        terminal(connV1).listPtyShells(connV1, "/home")

        coVerify(exactly = 1) { v2.listPtyShells(connV2, "/home") }
        coVerify(exactly = 1) { v1.listPtyShells(connV1, "/home") }
        coVerify(exactly = 0) { v1.listPtyShells(connV2, any()) }
        coVerify(exactly = 0) { v2.listPtyShells(connV1, any()) }
    }

    // ---------- File ----------

    @Test
    fun `file - V2 conn routes probeDirectory to v2 only`() = runTest {
        coEvery { v2.probeDirectory(connV2, "/home") } returns true

        assertTrue(file(connV2).probeDirectory(connV2, "/home"))

        coVerify(exactly = 1) { v2.probeDirectory(connV2, "/home") }
        coVerify(exactly = 0) { v1.probeDirectory(any(), any()) }
    }

    @Test
    fun `file - V1 conn routes probeDirectory to v1 only`() = runTest {
        coEvery { v1.probeDirectory(connV1, "/home") } returns false

        assertFalse(file(connV1).probeDirectory(connV1, "/home"))

        coVerify(exactly = 1) { v1.probeDirectory(connV1, "/home") }
        coVerify(exactly = 0) { v2.probeDirectory(any(), any()) }
    }

    @Test
    fun `file - searchText routes by conn`() = runTest {
        coEvery { v1.searchText(connV1, "kw") } returns emptyList()
        coEvery { v2.searchText(connV2, "kw") } returns emptyList()

        file(connV1).searchText(connV1, "kw")
        file(connV2).searchText(connV2, "kw")

        coVerify(exactly = 1) { v1.searchText(connV1, "kw") }
        coVerify(exactly = 1) { v2.searchText(connV2, "kw") }
        coVerify(exactly = 0) { v1.searchText(connV2, any()) }
        coVerify(exactly = 0) { v2.searchText(connV1, any()) }
    }

    @Test
    fun `file - getVcsDiff applies interface defaults through pick`() = runTest {
        coEvery { v2.getVcsDiff(connV2, "all", 3, null) } returns emptyList()

        file(connV2).getVcsDiff(connV2, "all")

        coVerify(exactly = 1) { v2.getVcsDiff(connV2, "all", 3, null) }
        coVerify(exactly = 0) { v1.getVcsDiff(any(), any(), any(), any()) }
    }

    // ---------- Provider ----------

    @Test
    fun `provider - V2 conn routes getProviders to v2 only`() = runTest {
        coEvery { v2.getProviders(connV2) } returns mockk()

        provider(connV2).getProviders(connV2)

        coVerify(exactly = 1) { v2.getProviders(connV2) }
        coVerify(exactly = 0) { v1.getProviders(any()) }
    }

    @Test
    fun `provider - V1 conn routes getProviders to v1 only`() = runTest {
        coEvery { v1.getProviders(connV1) } returns mockk()

        provider(connV1).getProviders(connV1)

        coVerify(exactly = 1) { v1.getProviders(connV1) }
        coVerify(exactly = 0) { v2.getProviders(any()) }
    }

    @Test
    fun `provider - completeProviderOauth applies interface default through pick`() = runTest {
        coEvery { v2.completeProviderOauth(connV2, "prov_1", 0, null) } returns true

        assertTrue(provider(connV2).completeProviderOauth(connV2, "prov_1", 0))

        coVerify(exactly = 1) { v2.completeProviderOauth(connV2, "prov_1", 0, null) }
        coVerify(exactly = 0) { v1.completeProviderOauth(any(), any(), any(), any()) }
    }

    @Test
    fun `provider - disposeGlobal routes by conn`() = runTest {
        coEvery { v1.disposeGlobal(connV1) } returns true
        coEvery { v2.disposeGlobal(connV2) } returns true

        provider(connV1).disposeGlobal(connV1)
        provider(connV2).disposeGlobal(connV2)

        coVerify(exactly = 1) { v1.disposeGlobal(connV1) }
        coVerify(exactly = 1) { v2.disposeGlobal(connV2) }
    }

    // ---------- Shell ----------

    @Test
    fun `shell - V1 conn degrades listShells inside v1 client`() = runTest {
        coEvery { v1.listShells(connV1, null) } returns emptyList()

        assertTrue(shell(connV1).listShells(connV1).isEmpty())

        coVerify(exactly = 1) { v1.listShells(connV1, null) }
        coVerify(exactly = 0) { v2.listShells(any(), any()) }
    }

    @Test
    fun `shell - V2 conn routes removeShell to v2 only`() = runTest {
        coEvery { v2.removeShell(connV2, "sh_1", null) } returns true

        assertTrue(shell(connV2).removeShell(connV2, "sh_1"))

        coVerify(exactly = 1) { v2.removeShell(connV2, "sh_1", null) }
        coVerify(exactly = 0) { v1.removeShell(any(), any()) }
    }

    // ---------- Message ----------

    @Test
    fun `message - V2 conn routes listMessages to v2 with before passthrough`() = runTest {
        coEvery { v2.listMessages(connV2, "ses_1", 50, "cur_1") } returns
            MessagePage(messages = emptyList(), nextCursor = null)

        message(connV2).listMessages(connV2, "ses_1", limit = 50, before = "cur_1")

        coVerify(exactly = 1) { v2.listMessages(connV2, "ses_1", 50, "cur_1") }
        coVerify(exactly = 0) { v1.listMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `message - V1 conn routes listMessages to v1 with before passthrough`() = runTest {
        coEvery { v1.listMessages(connV1, "ses_1", 50, "cur_1") } returns
            MessagePage(messages = emptyList(), nextCursor = null)

        message(connV1).listMessages(connV1, "ses_1", limit = 50, before = "cur_1")

        coVerify(exactly = 1) { v1.listMessages(connV1, "ses_1", 50, "cur_1") }
        coVerify(exactly = 0) { v2.listMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `message - replyToQuestion routes by conn with question passthrough`() = runTest {
        val q = questionFixture()
        val answers = listOf(listOf("Yes"))
        coEvery { v2.replyToQuestion(connV2, "frm_1", answers, null, q) } returns true
        coEvery { v1.replyToQuestion(connV1, "frm_1", answers, null, q) } returns true

        assertTrue(message(connV2).replyToQuestion(connV2, "frm_1", answers, null, q))
        assertTrue(message(connV1).replyToQuestion(connV1, "frm_1", answers, null, q))

        coVerify(exactly = 1) { v2.replyToQuestion(connV2, "frm_1", answers, null, q) }
        coVerify(exactly = 1) { v1.replyToQuestion(connV1, "frm_1", answers, null, q) }
        coVerify(exactly = 0) { v1.replyToQuestion(connV2, any(), any(), any(), any()) }
        coVerify(exactly = 0) { v2.replyToQuestion(connV1, any(), any(), any(), any()) }
    }

    @Test
    fun `message - rejectQuestion routes by conn with sessionId passthrough`() = runTest {
        coEvery { v2.rejectQuestion(connV2, "frm_1", null, "ses_1") } returns true
        coEvery { v1.rejectQuestion(connV1, "frm_1", null, null) } returns true

        assertTrue(message(connV2).rejectQuestion(connV2, "frm_1", sessionId = "ses_1"))
        assertTrue(message(connV1).rejectQuestion(connV1, "frm_1"))

        coVerify(exactly = 1) { v2.rejectQuestion(connV2, "frm_1", null, "ses_1") }
        coVerify(exactly = 1) { v1.rejectQuestion(connV1, "frm_1", null, null) }
        coVerify(exactly = 0) { v1.rejectQuestion(connV2, any(), any(), any()) }
        coVerify(exactly = 0) { v2.rejectQuestion(connV1, any(), any(), any()) }
    }

    @Test
    fun `message - V1 conn routes replyToPermission with sessionId`() = runTest {
        coEvery { v1.replyToPermission(connV1, "ses_1", "req_1", "once", null, null, null) } returns true

        assertTrue(message(connV1).replyToPermission(connV1, "ses_1", "req_1", "once"))

        coVerify(exactly = 1) { v1.replyToPermission(connV1, "ses_1", "req_1", "once", null, null, null) }
        coVerify(exactly = 0) { v2.replyToPermission(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ---------- 下沉适配的真实 client 守护（C1-3 字节等价） ----------

    private fun realV2(engine: MockEngine): V2ApiClient {
        val json = Json { ignoreUnknownKeys = true }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return V2ApiClient(ApiClient(client, json))
    }

    private fun questionFixture() = SseEvent.QuestionAsked(
        id = "frm_1",
        sessionId = "ses_sub",
        questions = listOf(
            SseEvent.QuestionAsked.Question(
                header = "Confirm",
                question = "Proceed?",
                options = listOf(SseEvent.QuestionAsked.Option(label = "Yes", description = "")),
                key = "q0"
            )
        )
    )

    @Test
    fun `v2 client listMessages translates before into server cursor param`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/ses_1/message", request.url.encodedPath)
            assertEquals("abc", request.url.parameters["cursor"])
            respond(
                """{"data":[],"cursor":{"previous":null,"next":null}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }

        val page = realV2(engine).listMessages(connV2, "ses_1", limit = 50, before = "abc")

        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun `v2 client replyToQuestion posts question-v2 reply with form id and session from question`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/ses_sub/question/frm_1/reply", request.url.encodedPath)
            respond(
                "{}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }

        val ok = realV2(engine).replyToQuestion(connV2, "frm_1", listOf(listOf("Yes")), null, questionFixture())

        assertTrue(ok)
    }

    @Test
    fun `v2 client replyToQuestion with null question returns false without http`() = runTest {
        val engine = MockEngine { _ -> throw AssertionError("question=null must not hit http") }

        assertFalse(realV2(engine).replyToQuestion(connV2, "frm_1", listOf(listOf("Yes")), null, null))
    }

    @Test
    fun `v2 client rejectQuestion with null sessionId returns false without http`() = runTest {
        val engine = MockEngine { _ -> throw AssertionError("sessionId=null must not hit http") }

        assertFalse(realV2(engine).rejectQuestion(connV2, "frm_1", null, null))
    }

    @Test
    fun `v2 client rejectQuestion posts question-v2 reject when sessionId present`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/session/ses_1/question/frm_1/reject", request.url.encodedPath)
            respond("{}", HttpStatusCode.OK)
        }

        assertTrue(realV2(engine).rejectQuestion(connV2, "frm_1", null, "ses_1"))
    }
    // ---------- DSH 三分路由（#276） ----------

    @Test
    fun `session - Dsh conn routes listSessions to dsh only regardless of apiVersion`() = runTest {
        coEvery { dsh.listSessions(connDsh, null, null, null, 50) } returns emptyList()

        session(connDsh).listSessions(connDsh)

        coVerify(exactly = 1) { dsh.listSessions(connDsh, null, null, null, 50) }
        coVerify(exactly = 0) { v1.listSessions(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { v2.listSessions(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `message - Dsh conn routes promptAsync to dsh only`() = runTest {
        coEvery { dsh.promptAsync(connDsh, "ses_1", any(), any(), any(), any(), any()) } returns null

        message(connDsh).promptAsync(connDsh, "ses_1", emptyList())

        coVerify(exactly = 1) { dsh.promptAsync(connDsh, "ses_1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { v1.promptAsync(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { v2.promptAsync(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `file - Dsh conn routes listDirectory to dsh only`() = runTest {
        coEvery { dsh.listDirectory(connDsh, "", null) } returns emptyList()

        file(connDsh).listDirectory(connDsh)

        coVerify(exactly = 1) { dsh.listDirectory(connDsh, "", null) }
        coVerify(exactly = 0) { v1.listDirectory(any(), any(), any()) }
        coVerify(exactly = 0) { v2.listDirectory(any(), any(), any()) }
    }
}