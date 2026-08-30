package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshEventMapper 帧映射测试（backlog #275 组件 A；设计文档 §1.5 帧词汇表 + §1.7 fold 决策）。
 *
 * 黄金样本：app/src/test/resources/dsh/
 * - mux-frames.jsonl：#274 既有 9 帧（信封形态已验证）
 * - mux-frames-extra.jsonl：#275 扩充帧（question 面 / host 面 / chunk 五子型 / 未知 SessionEvent）
 *
 * ID 契约（本组件写死，测试锁定）：
 * - 整装消息 id = "seq-{event.seq}"（跨重放稳定）；
 * - 实况流式消息 id = "dsh-t{turn}s{step}"（chunk 族宿主，assistant/message 到达时拆除）；
 * - 工具卡宿主消息 id = "dsh-call-{callId}"（tool/call 与 tool/result 的无状态连接键）；
 * - 文本 part id 委托 PartIdContract.derive(messageId, kind, ordinal)（ordinal = 块下标/块 index）。
 */
class DshEventMapperTest {

    private val json = Json

    private fun resourceText(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes().decodeToString()

    /** 黄金帧 → (method, payload, rpcId, 映射结果)。 */
    private fun mappedFrames(path: String): List<Mapoped> =
        resourceText(path).lineSequence().filter { it.isNotBlank() }.map { line ->
            val env = DshEnvelope.decode(line) as DshEnvelope.ServerRequest
            Mapoped(env.method, env.payload, env.rpcId, DshEventMapper.mapFrame(env.method, env.payload, env.rpcId))
        }.toList()

    /** 测试用四元组（method/payload/rpcId 携带进断言上下文）。 */
    private data class Mapoped(val method: String, val payload: JsonObject, val rpcId: String, val mapped: List<DshMappedEvent>)

    private fun sessionEvent(type: String, body: String = ""): JsonObject {
        val data = if (body.isEmpty()) "" else ""","data":$body"""
        return json.parseToJsonElement("""{"type":"$type","seq":100,"time":1788109999000$data}""").jsonObject
    }

    private fun eventsOf(mapped: List<DshMappedEvent>): List<SseEvent> =
        mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }

    // ============ mux 帧面：连接信号 ============

    @Test
    fun `session subscribed frame maps to baseline signal`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[0] // 黄金样本行 1：session/subscribed
        assertEquals("session/subscribed", m.method)
        assertEquals(listOf(DshMappedEvent.Subscribed(DshSubscribed(sessionId = "fixture-0001", lastSeq = 15L))), m.mapped)
    }

    // ============ mux 帧面：session/event 内层分派（实况流式） ============

    @Test
    fun `text delta chunk maps to part delta with streaming message id`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[1] // 黄金样本行 2：text-delta turn=2 step=1 index=1
        val delta = eventsOf(m.mapped).single() as SseEvent.MessagePartDelta
        // 流式宿主 id + PartIdContract 派生 part id（kind 编码进 id——#230 kind 推断依赖）
        assertEquals("fixture-0001", delta.sessionId)
        assertEquals("dsh-t2s1", delta.messageId)
        assertEquals("dsh-t2s1_text_ord_1", delta.partId)
        assertEquals("text", delta.field)
        assertEquals("live", delta.delta)
    }

    @Test
    fun `block start chunk seeds reasoning part with start time`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[7]
        val part = (eventsOf(m.mapped).single() as SseEvent.MessagePartUpdated).part as Part.Reasoning
        assertEquals("dsh-t2s1_reasoning_ord_0", part.id)
        assertEquals("fixture-0001", part.sessionId)
        assertEquals("dsh-t2s1", part.messageId)
        assertEquals("", part.text)
        assertEquals(1788109002000L, part.time!!.start)
        assertEquals(null, part.time!!.end) // 未终态——终态化由 turn/end 的 idle 路径接管
    }

    @Test
    fun `reasoning delta chunk maps field reasoning`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[8]
        val delta = eventsOf(m.mapped).single() as SseEvent.MessagePartDelta
        assertEquals("dsh-t2s1_reasoning_ord_0", delta.partId)
        assertEquals("reasoning", delta.field)
        assertEquals("live think", delta.delta)
    }

    @Test
    fun `block end chunk is ignored to avoid terminal wipe`() {
        // 偏离任务草案的定点裁决：DSH block-end 不携带文本，而 mergePart 的 isTerminal
        // 覆盖语义假定 incoming 是全量终值（2026-08-16 官方 text.ended 契约）——发空文本
        // 终态 part 会清空已流式文本。终态化改由 turn/end → SessionIdle → markSessionIdle 承担。
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[9]
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_BLOCK_END)), m.mapped)
    }

    @Test
    fun `usage chunk is ignored for 276 session usage`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[10]
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_USAGE)), m.mapped)
    }

    @Test
    fun `unknown session event type inside frame is unignorable`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[11] // team/task
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.UNKNOWN_UNIGNORABLE)), m.mapped)
    }

    // ============ mux 帧面：approval / question ============

    @Test
    fun `approval requested maps to PermissionAsked keyed by approvalId`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[3]
        val asked = eventsOf(m.mapped).single() as SseEvent.PermissionAsked
        assertEquals(
            SseEvent.PermissionAsked(
                id = "appr-fixture-1",
                sessionId = "fixture-0001",
                permission = "bash",
                metadata = mapOf("callId" to "call_fixture_2", "reason" to "escalate sandbox for fixture"),
            ),
            asked,
        )
    }

    @Test
    fun `approval resolved maps to PermissionReplied`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[3]
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.PermissionReplied(sessionId = "fixture-0001", requestId = "appr-fixture-1"))),
            m.mapped,
        )
    }

    @Test
    fun `question requested maps multi question single event with rpcId fallback id`() {
        // DSH question 帧无帧级载荷 id——回程路由键是信封 rpcId（§1.6-6 pending 注册表）
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[0]
        val asked = eventsOf(m.mapped).single() as SseEvent.QuestionAsked
        assertEquals("22222222-0000-0000-0000-000000000001", asked.id)
        assertEquals("fixture-0001", asked.sessionId)
        assertEquals(
            listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Confirm",
                    question = "Deploy now?",
                    multiple = false,
                    custom = true,
                    options = listOf(
                        SseEvent.QuestionAsked.Option("Ship it", "release immediately"),
                        SseEvent.QuestionAsked.Option("Hold", "wait for QA"),
                    ),
                    key = "q-alpha",
                ),
                SseEvent.QuestionAsked.Question(
                    header = "",
                    question = "Which regions?",
                    multiple = true,
                    custom = true,
                    options = listOf(
                        SseEvent.QuestionAsked.Option("EU", "frankfurt"),
                        SseEvent.QuestionAsked.Option("US", "virginia"),
                    ),
                    key = "q-beta",
                ),
            ),
            asked.questions,
        )
    }

    @Test
    fun `question resolved answered maps replied and cancelled maps rejected`() {
        val answered = mappedFrames("dsh/mux-frames-extra.jsonl")[1]
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.QuestionReplied("fixture-0001", "22222222-0000-0000-0000-000000000002"))),
            answered.mapped,
        )
        val cancelled = mappedFrames("dsh/mux-frames-extra.jsonl")[2]
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.QuestionRejected("fixture-0001", "22222222-0000-0000-0000-000000000003"))),
            cancelled.mapped,
        )
    }

    // ============ mux 帧面：显式忽略（#276/后续承接） ============

    @Test
    fun `queue jobs projection and stream error frames are ignored with named reasons`() {
        val golden = mappedFrames("dsh/mux-frames.jsonl")
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.PROJECTION)), golden[2].mapped)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.QUEUE)), golden[4].mapped)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.JOBS)), golden[5].mapped)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.STREAM_ERROR)), golden[8].mapped)
    }

    // ============ host 帧面 ============

    @Test
    fun `host session added maps to minimal SessionCreated`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[7]
        val created = eventsOf(m.mapped).single() as SseEvent.SessionCreated
        // 最小构造：cwd→directory、parentSessionId→parentId；帧无时间字段 → epoch0 占位
        //（#276 由 session.list 再基线补全——设计 §2.2 能力位配套）
        assertEquals("fixture-0002", created.info.id)
        assertEquals("/home/user/project", created.info.directory)
        assertEquals(null, created.info.parentId)
        assertEquals(0L, created.info.time.created)
        assertEquals(0L, created.info.time.updated)
    }

    @Test
    fun `host session removed maps to SessionDeleted`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[4]
        val deleted = eventsOf(m.mapped).single() as SseEvent.SessionDeleted
        assertEquals("fixture-0002", deleted.info.id)
    }

    @Test
    fun `host session status maps running flag to busy and idle`() {
        val idle = mappedFrames("dsh/mux-frames.jsonl")[6]
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.SessionStatus("fixture-0001", SessionStatus.Idle))),
            idle.mapped,
        )
        val busy = DshEventMapper.mapFrame(
            "host/session-status",
            json.parseToJsonElement("""{"type":"host/session-status","sessionId":"s1","running":true}""").jsonObject,
        )
        assertEquals(listOf(DshMappedEvent.Sse(SseEvent.SessionStatus("s1", SessionStatus.Busy))), busy)
    }

    @Test
    fun `host agent error maps to SessionError with message`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[5]
        val error = eventsOf(m.mapped).single() as SseEvent.SessionError
        assertEquals("fixture-0001", error.sessionId)
        assertEquals("fixture agent crash", error.error)
    }

    @Test
    fun `host workspace frames and unknown frame methods are ignored`() {
        val ws = mappedFrames("dsh/mux-frames-extra.jsonl")[6]
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.HOST_WORKSPACE)), ws.mapped)
        val unknown = DshEventMapper.mapFrame("host/archived-sessions-changed", json.parseToJsonElement("""{"type":"host/archived-sessions-changed"}""").jsonObject)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.HOST_WORKSPACE)), unknown)
        val alien = DshEventMapper.mapFrame("some/future-method", json.parseToJsonElement("""{"type":"some/future-method"}""").jsonObject)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.FRAME_METHOD)), alien)
    }

    // ============ SessionEvent 内层：消息族（历史/实况同路径） ============

    @Test
    fun `user message maps to MessageUpdated with seq id and terminal text part`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "fixture-0001",
            sessionEvent(
                "user/message",
                """{"content":[{"type":"text","text":"hello beacon"}],"source":{"kind":"user"}}""",
            ),
        )
        assertEquals(2, mapped.size)
        val user = (mapped[0] as DshMappedEvent.Sse).event as SseEvent.MessageUpdated
        assertEquals(
            Message.User(id = "seq-100", sessionId = "fixture-0001", time = TimeInfo(created = 1788109999000L)),
            user.info,
        )
        val part = (mapped[1] as DshMappedEvent.Sse).event as SseEvent.MessagePartUpdated
        assertEquals(
            Part.Text(
                id = "seq-100_text_ord_0",
                sessionId = "fixture-0001",
                messageId = "seq-100",
                text = "hello beacon",
                time = Part.Text.Time(start = 1788109999000L, end = 1788109999000L),
            ),
            part.part,
        )
    }

    @Test
    fun `assistant message maps with completed time tokens content parts and streaming bridge`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "fixture-0001",
            sessionEvent(
                "assistant/message",
                """{"turn":3,"step":2,"message":{"role":"assistant","content":[{"type":"reasoning","text":"why"},{"type":"text","text":"answer body"}]},"usage":{"inputTokens":10,"outputTokens":5}}""",
            ),
        )
        // 桥接拆除 → 消息 → reasoning part → text part
        assertEquals(4, mapped.size)
        val removed = (mapped[0] as DshMappedEvent.Sse).event as SseEvent.MessageRemoved
        assertEquals("fixture-0001", removed.sessionId)
        assertEquals("dsh-t3s2", removed.messageId) // 同 turn/step 的实况流式宿主被整装替换
        val msg = (mapped[1] as DshMappedEvent.Sse).event as SseEvent.MessageUpdated
        val assistant = msg.info as Message.Assistant
        assertEquals("seq-100", assistant.id)
        assertEquals(TimeInfo(created = 1788109999000L, completed = 1788109999000L), assistant.time) // 红点水位线依赖 completed（§2.3）
        assertEquals(10, assistant.tokens!!.input)
        assertEquals(5, assistant.tokens!!.output)
        assertEquals(15, assistant.tokens!!.total)
        val reasoning = ((mapped[2] as DshMappedEvent.Sse).event as SseEvent.MessagePartUpdated).part as Part.Reasoning
        assertEquals("seq-100_reasoning_ord_0", reasoning.id)
        assertEquals("why", reasoning.text)
        assertEquals(1788109999000L, reasoning.time!!.end) // 整装即终态（#266 迟到 delta 守卫）
        val text = ((mapped[3] as DshMappedEvent.Sse).event as SseEvent.MessagePartUpdated).part as Part.Text
        assertEquals("seq-100_text_ord_1", text.id)
        assertEquals("answer body", text.text)
    }

    @Test
    fun `tool call maps to pending tool card on call scoped host message`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "fixture-0001",
            sessionEvent("tool/call", """{"turn":3,"step":2,"callId":"call_1","name":"bash","arguments":"{\"command\":\"ls\"}"}"""),
        )
        assertEquals(2, mapped.size)
        val host = ((mapped[0] as DshMappedEvent.Sse).event as SseEvent.MessageUpdated).info as Message.Assistant
        assertEquals("dsh-call-call_1", host.id) // tool/result 的无状态连接键
        assertEquals(1788109999000L, host.time.created)
        val tool = ((mapped[1] as DshMappedEvent.Sse).event as SseEvent.MessagePartUpdated).part as Part.Tool
        assertEquals("call_1", tool.id)
        assertEquals("call_1", tool.callId)
        assertEquals("bash", tool.tool)
        assertEquals("dsh-call-call_1", tool.messageId)
        val pending = tool.state as ToolState.Pending
        assertEquals("""{"command":"ls"}""", pending.raw) // 原始参数串保真
        assertEquals(setOf("command"), pending.input.keys) // 可解析时同步展开 input map
        assertEquals("ls", (pending.input["command"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `tool result completes the tool card via call join key`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "fixture-0001",
            sessionEvent(
                "tool/result",
                """{"turn":3,"step":2,"message":{"source":{"kind":"tool","callId":"call_1"},"content":[{"type":"tool-result","toolCallId":"call_1","content":[{"type":"text","text":"file-a\nfile-b"}]}]}}""",
            ),
        )
        val tool = (eventsOf(mapped).single() as SseEvent.MessagePartUpdated).part as Part.Tool
        assertEquals("call_1", tool.id)
        assertEquals("dsh-call-call_1", tool.messageId)
        val completed = tool.state as ToolState.Completed
        assertEquals("file-a\nfile-b", completed.output)
    }

    @Test
    fun `tool result error payload maps to error state`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "fixture-0001",
            sessionEvent(
                "tool/result",
                """{"turn":3,"step":2,"error":{"code":"command-error","message":"exit 1"},"message":{"source":{"kind":"tool","callId":"call_2"},"content":[]}}""",
            ),
        )
        val tool = (eventsOf(mapped).single() as SseEvent.MessagePartUpdated).part as Part.Tool
        val error = tool.state as ToolState.Error
        assertEquals("exit 1", error.error)
    }

    // ============ SessionEvent 内层：会话态族 ============

    @Test
    fun `turn and step start map to busy status and turn end maps to idle`() {
        // 节流（重复 busy 的 FSM 去重）留给 #276 编排层
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.SessionStatus("s9", SessionStatus.Busy))),
            DshEventMapper.mapSessionEvent("s9", sessionEvent("turn/start", """{"turn":1}""")),
        )
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.SessionStatus("s9", SessionStatus.Busy))),
            DshEventMapper.mapSessionEvent("s9", sessionEvent("step/start", """{"turn":1,"step":1}""")),
        )
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.SessionIdle("s9"))),
            DshEventMapper.mapSessionEvent("s9", sessionEvent("turn/end", """{"turn":1,"reason":{"kind":"completed"}}""")),
        )
    }

    @Test
    fun `todo write maps to TodoUpdated full snapshot`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("todo/write", """{"todos":[{"content":"a","status":"pending"},{"content":"b","status":"completed"}]}"""),
        )
        val todo = eventsOf(mapped).single() as SseEvent.TodoUpdated
        assertEquals("s9", todo.sessionId)
        assertEquals(
            listOf(
                SseEvent.TodoUpdated.Todo(content = "a", status = "pending", priority = "medium"),
                SseEvent.TodoUpdated.Todo(content = "b", status = "completed", priority = "medium"),
            ),
            todo.todos,
        ) // DSH 无优先级字段 → "medium"（V1 MiscEventParser 同默认）
    }

    @Test
    fun `session title maps to SessionUpdated with title`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("session/title", """{"title":"renamed","messageSeqs":[5],"source":{"kind":"fallback"}}"""),
        )
        val updated = eventsOf(mapped).single() as SseEvent.SessionUpdated
        assertEquals("s9", updated.info.id)
        assertEquals("renamed", updated.info.title)
        assertEquals(1788109999000L, updated.info.time.updated) // 事件时刻驱动列表排序位
    }

    // ============ Tier2 / Tier3 目录：具名忽略 ============

    @Test
    fun `tier2 catalog types are ignored with named reasons`() {
        val cases = mapOf(
            "compaction/start" to DshIgnoreReason.COMPACTION,
            "compaction/end" to DshIgnoreReason.COMPACTION,
            "compaction/summary" to DshIgnoreReason.COMPACTION,
            "compaction/prune" to DshIgnoreReason.COMPACTION,
            "goal/change" to DshIgnoreReason.GOAL_CHANGE,
            "subagent/descriptor" to DshIgnoreReason.SUBAGENT_DESCRIPTOR,
            "agent-preset/selected" to DshIgnoreReason.AGENT_PRESET,
        )
        cases.forEach { (type, reason) ->
            assertEquals("type=$type", listOf(DshMappedEvent.Ignored(reason)), DshEventMapper.mapSessionEvent("s9", sessionEvent(type)))
        }
    }

    @Test
    fun `protocol noise types are ignored without becoming unignorable`() {
        // 高频伴生事件（§1.7 实测分布）：不进目录会让几乎所有真实会话拒绝重建
        val noise = listOf(
            "sandbox/mode", "approval/policy", "permission/preset", "plan/mode",
            "agent/inbox/spliced", "step/end", "llm/retry", "llm/retry-started",
            "command/run", "command/done", "request/header", "request/context",
            "session/end-seed", "tool/code-dispatch", "tool/code-dispatch-start",
            "approval/asked", "approval/decided", "web/deepseek-search-llm-request",
            "schedule/change", "feedback/record",
        )
        noise.forEach { type ->
            val mapped = DshEventMapper.mapSessionEvent("s9", sessionEvent(type))
            assertEquals("type=$type", 1, mapped.size)
            val ignored = mapped.single() as DshMappedEvent.Ignored
            assertTrue("type=$type 不得落入 unignorable", ignored.reason != DshIgnoreReason.UNKNOWN_UNIGNORABLE)
        }
    }

    // ============ 容错（mapper 不抛异常——设计 §1.6-7 开放联合） ============

    @Test
    fun `malformed payloads never throw and degrade to ignored`() {
        val malformed = listOf(
            "session/event" to """{"type":"session/event","sessionId":"s1"}""", // 缺 event
            "session/subscribed" to """{"type":"session/subscribed"}""", // 缺 sessionId/lastSeq
            "question/resolved" to """{"type":"question/resolved","sessionId":"s1"}""", // 缺回程 id
            "host/session-status" to """{"type":"host/session-status","sessionId":"s1"}""", // 缺 running
        )
        malformed.forEach { (method, payload) ->
            val mapped = DshEventMapper.mapFrame(method, json.parseToJsonElement(payload).jsonObject)
            assertTrue(mapped.all { it is DshMappedEvent.Ignored })
        }
        // 缺 seq/time/data 的 SessionEvent：降级为 seq-0 + 空数据，不抛
        val degenerate = DshEventMapper.mapSessionEvent("s1", json.parseToJsonElement("""{"type":"user/message"}""").jsonObject)
        assertTrue(degenerate.isNotEmpty())
        val toolNoCallId = DshEventMapper.mapSessionEvent("s1", json.parseToJsonElement("""{"type":"tool/call","seq":1,"time":1,"data":{}}""").jsonObject)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED)), toolNoCallId)
        val chunkNoBody = DshEventMapper.mapSessionEvent("s1", json.parseToJsonElement("""{"type":"assistant/chunk","seq":1,"time":1,"data":{}}""").jsonObject)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED)), chunkNoBody)
    }
}
