package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistry
import dev.leonardo.ocbeacon.data.repository.StubCollaborator
import dev.leonardo.ocbeacon.data.repository.UnreadBadgeService
import dev.leonardo.ocbeacon.data.repository.UnreadStateStore
import dev.leonardo.ocbeacon.data.repository.HistorySyncManager
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import kotlinx.coroutines.cancel
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * #349 DSH 面 subagent 卡全管线钉死：真 wire 载荷（fb650391 turn 8 实录）经
 * DshEventMapper → EventDispatcher/MessageEventHandler，断言最终 part 状态。
 *
 * 动机（2026-09-07 真机）：mapper 单测绿但设备上 fg 卡 metadata=null——需要
 * 管线级（mergePart/注册决策树）证据定位丢失层。
 */
class DshSubagentCard349PipelineTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var messageHandler: MessageEventHandler
    private lateinit var scope: TestScope

    private val json = Json

    @Before
    fun setup() {
        scope = TestScope(UnconfinedTestDispatcher())
        messageHandler = MessageEventHandler()
        dispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageHandler,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(TokenStatsTracker()),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageHandler),
            sessionStateRepository = SessionStateService(
                appScope = scope,
                sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
                collaborator = StubCollaborator(),
                cursorPolicyFactory = PaginationCursorPolicyFactory(
                    Provider { mockk<SessionRepository>(relaxed = true) },
                ),
            ),
            unreadBadgeService = UnreadBadgeService(
                mockk<UnreadStateStore>(relaxed = true),
                CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
            ),
            ownershipRegistry = StreamingOwnershipRegistry(),
            permissionAutoApprover = mockk(relaxed = true),
            pendingInteractionStore = mockk(relaxed = true),
            stackedMessageStore = mockk(relaxed = true),
            historySyncManagerProvider = Provider { mockk<HistorySyncManager>(relaxed = true) },
            dshJobsHandler = mockk(relaxed = true),
            dshQueueHandler = mockk(relaxed = true),
            dshWorkspaceHandler = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun feed(sessionId: String, wire: String) {
        val envelope = json.parseToJsonElement(wire).jsonObject
        DshEventMapper.mapSessionEvent(sessionId, envelope).forEach { mapped ->
            when (mapped) {
                is DshMappedEvent.Sse -> dispatcher.processEvent(mapped.event, "srv-dsh")
                else -> Unit
            }
        }
    }

    private fun subCard(): Part.Tool? =
        messageHandler.parts.value["dsh-call-call_c641a3dcb11349a4bbc04b79:subagent"]
            ?.filterIsInstance<Part.Tool>()
            ?.firstOrNull { it.id == "call_c641a3dcb11349a4bbc04b79:subagent" }

    @Test
    fun `fg dispatch chain lands metadata on final card`() = runTest {
        val sid = "session-fb650391"
        // 12553 tool/call（run_code 根）
        feed(sid, """{"type":"tool/call","seq":12553,"time":1788783538903,"data":{"turn":8,"step":1,"callId":"call_c641a3dcb11349a4bbc04b79","name":"run_code","arguments":"{\\\"code\\\":\\\"await tools.subagent({description:\\\\\\\"Run trivial subagent test\\\\\\\"})\\\"}"}}""")
        // 12554 tool/code-dispatch-start（subagent）
        feed(sid, """{"type":"tool/code-dispatch-start","seq":12554,"time":1788783538938,"data":{"rootCallId":"call_c641a3dcb11349a4bbc04b79","parentCallId":"call_c641a3dcb11349a4bbc04b79","subCallId":"call_c641a3dcb11349a4bbc04b79:code:1","name":"subagent","arguments":{"description":"Run trivial subagent test","prompt":"connectivity test","run_in_background":false}}}""")
        // 12555 tool/code-dispatch（fg 报告，无 id）
        feed(sid, """{"type":"tool/code-dispatch","seq":12555,"time":1788783549825,"data":{"rootCallId":"call_c641a3dcb11349a4bbc04b79","parentCallId":"call_c641a3dcb11349a4bbc04b79","subCallId":"call_c641a3dcb11349a4bbc04b79:code:1","name":"subagent","arguments":{"description":"Run trivial subagent test","prompt":"connectivity test","run_in_background":false},"isError":false,"content":[{"type":"text","text":"1. 17 × 23 = **391**"}]}}""")
        // 12556 tool/result（根信封 {kind:foreground,runId,output}）——信封为干净 JSON
        // 嵌套进 text 字段（与 wire 一致：服务器序列化后的字符串值）。
        val envelopeJson = "{\"kind\":\"foreground\",\"runId\":\"219905a7-5819-4d06-872f-f4df1f60f5e4\",\"output\":[{\"type\":\"text\",\"text\":\"Done: 391\"}]}"
        val envelopeEmbedded = envelopeJson.replace("\"", "\\\"")
        val resultPayload = """{"type":"tool/result","seq":12556,"time":1788783549831,"data":{"turn":8,"step":1,"message":{"source":{"kind":"tool","callId":"call_c641a3dcb11349a4bbc04b79"},"content":[{"type":"tool-result","toolCallId":"call_c641a3dcb11349a4bbc04b79","content":[{"type":"text","text":"$envelopeEmbedded"}]}]}}}"""
        feed(sid, resultPayload)

        val card = subCard()
        assertNotNull("subagent 卡应存在", card)
        val completed = card!!.state as ToolState.Completed
        assertEquals("219905a7-5819-4d06-872f-f4df1f60f5e4", (completed.metadata?.get("sessionId") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `history fold assemble path preserves link metadata`() = runTest {
        val envelopeJson = "{\"kind\":\"foreground\",\"runId\":\"219905a7-5819-4d06-872f-f4df1f60f5e4\",\"output\":[{\"type\":\"text\",\"text\":\"Done: 391\"}]}"
        val envelopeEmbedded = envelopeJson.replace("\"", "\\\"")
        val rows = listOf(
            """{"event":{"type":"tool/call","seq":12553,"time":1788783538903,"data":{"turn":8,"step":1,"callId":"call_c641a3dcb11349a4bbc04b79","name":"run_code","arguments":"{}"}}}""",
            """{"event":{"type":"tool/code-dispatch-start","seq":12554,"time":1788783538938,"data":{"rootCallId":"call_c641a3dcb11349a4bbc04b79","subCallId":"call_c641a3dcb11349a4bbc04b79:code:1","name":"subagent","arguments":{"description":"Run trivial subagent test"}}}}""",
            """{"event":{"type":"tool/code-dispatch","seq":12555,"time":1788783549825,"data":{"rootCallId":"call_c641a3dcb11349a4bbc04b79","subCallId":"call_c641a3dcb11349a4bbc04b79:code:1","name":"subagent","arguments":{},"isError":false,"content":[{"type":"text","text":"1. 17 × 23 = **391**"}]}}}""",
            """{"event":{"type":"tool/result","seq":12556,"time":1788783549831,"data":{"turn":8,"step":1,"message":{"source":{"kind":"tool","callId":"call_c641a3dcb11349a4bbc04b79"},"content":[{"type":"tool-result","toolCallId":"call_c641a3dcb11349a4bbc04b79","content":[{"type":"text","text":"$envelopeEmbedded"}]}]}}}}""",
        ).map { json.parseToJsonElement(it).jsonObject }
        val fold = DshHistoryFolder.fold(rows, "session-fb650391")
        assertEquals(emptyList<String>(), fold.unknownUnignorable)
        val assembled = DshMessageAssembler.assemble(fold.sseEvents)
        val subMsg = assembled.first { it.info.id == "dsh-call-call_c641a3dcb11349a4bbc04b79:subagent" }
        // 装配面同一 part id 只应留一份终态（与 dispatcher merge 等价——
        // #349 前按到达序 append，同 id 多份 ×N 角标 + 陈旧首份胜出）。
        val toolParts = subMsg.parts.filterIsInstance<Part.Tool>()
        assertEquals(1, toolParts.size)
        val md = (toolParts.single().state as ToolState.Completed).metadata
        assertEquals("219905a7-5819-4d06-872f-f4df1f60f5e4", (md?.get("sessionId") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `bg dispatch chain carries run id immediately`() = runTest {
        val sid = "session-c6de9470"
        feed(sid, """{"type":"tool/code-dispatch-start","seq":535,"time":1788603222562,"data":{"rootCallId":"call_fc09a6380ee84bd3a6c9c336","parentCallId":"call_fc09a6380ee84bd3a6c9c336","subCallId":"call_fc09a6380ee84bd3a6c9c336:code:1","name":"subagent","arguments":{"description":"Count slowly to 10, report"}}}""")
        feed(sid, """{"type":"tool/code-dispatch","seq":536,"time":1788603222563,"data":{"rootCallId":"call_fc09a6380ee84bd3a6c9c336","parentCallId":"call_fc09a6380ee84bd3a6c9c336","subCallId":"call_fc09a6380ee84bd3a6c9c336:code:1","name":"subagent","arguments":{"description":"Count slowly to 10, report"},"isError":false,"content":[{"type":"text","text":"started subagent bd5a33c9-cd05-4d73-baff-2319c036681e"}]}}""")
        val card = messageHandler.parts.value["dsh-call-call_fc09a6380ee84bd3a6c9c336:subagent"]
            ?.filterIsInstance<Part.Tool>()
            ?.firstOrNull()
        assertNotNull(card)
        val completed = card!!.state as ToolState.Completed
        assertEquals("bd5a33c9-cd05-4d73-baff-2319c036681e", (completed.metadata?.get("sessionId") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }
}
