package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.JobView
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
import org.junit.Assert.assertNull
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
    fun `session subscribed frame maps to baseline signal and clears jobs`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[0] // 黄金样本行 1：session/subscribed
        assertEquals("session/subscribed", m.method)
        // 对齐官方 client.js:8314：subscribed 基线先行清空 jobs，服务器随后重推快照
        assertEquals(
            listOf(
                DshMappedEvent.Subscribed(DshSubscribed(sessionId = "fixture-0001", lastSeq = 15L)),
                DshMappedEvent.Sse(SseEvent.JobsSnapshot(sessionId = "fixture-0001", jobs = emptyList())),
            ),
            m.mapped,
        )
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

    @Test
    fun `unknown type with ignorable flag honors the flag`() {
        // E2E 后兑现（2026-08-31）：信封 ignorable:true 是权威安全信号
        val envelope = json.parseToJsonElement(
            """{"type":"future/plugin-event","seq":99,"time":1,"ignorable":true,"data":{}}"""
        ).jsonObject
        val mapped = DshEventMapper.mapSessionEvent("s1", envelope)
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.IGNORABLE_FLAG)), mapped)
    }

    @Test
    fun `tool-call-delta and finish chunk subtypes are lifecycle-ignored`() {
        // E2E 实况情报：spec 五子型之外的两个子型，静默不告警
        listOf("tool-call-delta", "finish").forEach { sub ->
            val envelope = json.parseToJsonElement(
                """{"type":"assistant/chunk","seq":1,"time":1,"data":{"turn":1,"step":1,"chunk":{"type":"$sub","index":0}}}"""
            ).jsonObject
            val mapped = DshEventMapper.mapSessionEvent("s1", envelope)
            assertEquals("sub=$sub", listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_LIFECYCLE)), mapped)
        }
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
                // #276 接线注意①：信封 rpcId 入 metadata（/api/respond 回程路由键）
                metadata = mapOf(
                    "callId" to "call_fixture_2",
                    "reason" to "escalate sandbox for fixture",
                    "rpcId" to "11111111-0000-0000-0000-000000000004",
                ),
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

    // ============ mux 帧面：jobs / projection（A/B 接线） ============

    @Test
    fun `jobs frame maps to JobsSnapshot with JobView fields`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[5] // session/jobs 2 条
        val snapshot = eventsOf(m.mapped).single() as SseEvent.JobsSnapshot
        assertEquals("fixture-0001", snapshot.sessionId)
        assertEquals(
            listOf(
                JobView(
                    id = "job-1", kind = "bash", label = "npm test", status = "running",
                    detail = "run unit tests", startedAt = 1000L, finishedAt = null,
                ),
                JobView(
                    id = "job-2", kind = "subagent", label = "scout", status = "completed",
                    detail = null, startedAt = 2000L, finishedAt = 3000L,
                ),
            ),
            snapshot.jobs,
        )
    }

    @Test
    fun `empty jobs frame maps to JobsSnapshot empty for last-wins clear`() {
        val mapped = DshEventMapper.mapFrame(
            "session/jobs",
            json.parseToJsonElement("""{"type":"session/jobs","sessionId":"s1","jobs":[]}""").jsonObject,
        )
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.JobsSnapshot(sessionId = "s1", jobs = emptyList()))),
            mapped,
        )
    }

    @Test
    fun `projection tokenUsage frame maps to SessionTokenUsageChanged`() {
        val m = mappedFrames("dsh/mux-frames.jsonl")[2] // key=tokenUsage
        val changed = eventsOf(m.mapped).single() as SseEvent.SessionTokenUsageChanged
        assertEquals("fixture-0001", changed.sessionId)
        assertEquals(
            dev.leonardo.ocbeacon.domain.model.DshTokenUsage(
                uncachedInputTokens = 100L, outputTokens = 50L, cacheReadTokens = 20L, cacheWriteTokens = 0L,
            ),
            changed.tokenUsage,
        )
        assertEquals(170L, changed.tokenUsage.total)
    }

    @Test
    fun `projection subagentTiming frame maps to SessionSubagentTimingChanged`() {
        val mapped = DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                """{"type":"session/projection","sessionId":"s1","key":"subagentTiming","value":{"settledMs":1500,"active":{"since":1000,"through":2500}},"seq":9}"""
            ).jsonObject,
        )
        val changed = eventsOf(mapped).single() as SseEvent.SessionSubagentTimingChanged
        assertEquals("s1", changed.sessionId)
        assertEquals(
            dev.leonardo.ocbeacon.domain.model.DshSubagentTiming(settledMs = 1500L, activeSince = 1000L, activeThrough = 2500L),
            changed.timing,
        )
        assertEquals(3000L, changed.timing.activeDurationMs) // settled 1500 + active(2500-1000)
    }

    @Test
    fun `projection subagentTiming without active maps settled only`() {
        val mapped = DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                """{"type":"session/projection","sessionId":"s1","key":"subagentTiming","value":{"settledMs":42},"seq":9}"""
            ).jsonObject,
        )
        val changed = eventsOf(mapped).single() as SseEvent.SessionSubagentTimingChanged
        assertEquals(42L, changed.timing.activeDurationMs) // 无 active → settled 原值
        assertEquals(null, changed.timing.activeSince)
    }

    @Test
    fun `projection unknown key is ignored with projection reason`() {
        val mapped = DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                """{"type":"session/projection","sessionId":"s1","key":"todos","value":{"items":[]},"seq":9}"""
            ).jsonObject,
        )
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.PROJECTION)), mapped)
    }

    @Test
    fun `queue and stream error frames are ignored with named reasons`() {
        val golden = mappedFrames("dsh/mux-frames.jsonl")
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.QUEUE)), golden[4].mapped)
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
    fun `host agent-error wire shape carries message key per dsh schema`() {
        // DSH v0.1.1-rc.2 真实现（events.schema.js:72 / apiproxy index.js 帧发射）载荷键是
        // message（字符串），旧 fixture 的 error 对象形态是假设产物。键失配时 errorText
        // 返回字面量 "error"，真实错误文本（如欠费/provider 拒绝）被吞 → 会话静默终止。
        val mapped = DshEventMapper.mapFrame(
            "host/agent-error",
            json.parseToJsonElement(
                """{"type":"host/agent-error","sessionId":"fixture-0001","message":"provider rejected request: insufficient balance"}""", 
            ).jsonObject,
        )
        val error = eventsOf(mapped).single() as SseEvent.SessionError
        assertEquals("fixture-0001", error.sessionId)
        assertEquals("provider rejected request: insufficient balance", error.error)
    }

    @Test
    fun `host agent-error legacy error-object shape still maps for compat`() {
        // 旧黄金样本（mux-frames-extra.jsonl:6 前态）的 error 对象形态保留兼容（message 优先回退）。
        val mapped = DshEventMapper.mapFrame(
            "host/agent-error",
            json.parseToJsonElement(
                """{"type":"host/agent-error","sessionId":"s1","error":{"code":"internal","message":"fixture agent crash"}}""", 
            ).jsonObject,
        )
        val error = eventsOf(mapped).single() as SseEvent.SessionError
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

    /**
     * #276 后端接口补全：compaction/end → SessionCompacted（压缩完成信号——
     * SessionEventHandler.compactedSessions 计数 → ChatViewModel 刷新 + 完成
     * snackbar 依赖此事件；对位 V2 session.compaction.ended 的映射先例）。
     */
    @Test
    fun `compaction end maps to SessionCompacted`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("compaction/end", """{"turn":3,"summarySeq":42}"""),
        )
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.SessionCompacted(sessionId = "s9"))),
            mapped,
        )
    }

    /** 实况帧路径（fixture 黄金样本）：session/event 包 compaction/end 同映射。 */
    @Test
    fun `compaction end frame maps to SessionCompacted`() {
        val m = mappedFrames("dsh/mux-frames-extra.jsonl")[12] // 黄金样本行 13
        assertEquals("session/event", m.method)
        assertEquals(
            listOf(DshMappedEvent.Sse(SseEvent.SessionCompacted(sessionId = "fixture-0001"))),
            m.mapped,
        )
    }

    // ============ 三 knob 事件 → SessionPermissionChanged ============

    @Test
    fun `permission preset event maps to SessionPermissionChanged with preset`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("permission/preset", """{"preset":"danger-full-access"}"""),
        )
        val changed = eventsOf(mapped).single() as SseEvent.SessionPermissionChanged
        assertEquals("s9", changed.sessionId)
        assertEquals("danger-full-access", changed.preset)
        assertNull(changed.sandboxMode)
        assertNull(changed.approvalPolicy)
    }

    @Test
    fun `sandbox mode event maps to SessionPermissionChanged with sandbox`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("sandbox/mode", """{"mode":"workspace-write"}"""),
        )
        val changed = eventsOf(mapped).single() as SseEvent.SessionPermissionChanged
        assertEquals("workspace-write", changed.sandboxMode)
        assertNull(changed.preset)
        assertNull(changed.approvalPolicy)
    }

    @Test
    fun `approval policy event maps to SessionPermissionChanged with policy`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("approval/policy", """{"policy":"never","source":"delegation"}"""),
        )
        val changed = eventsOf(mapped).single() as SseEvent.SessionPermissionChanged
        assertEquals("never", changed.approvalPolicy)
        assertNull(changed.preset)
        assertNull(changed.sandboxMode)
    }

    @Test
    fun `agent preset selected event maps to SessionAgentPresetChanged`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s9",
            sessionEvent("agent-preset/selected", """{"agentPreset":"cordis"}"""),
        )
        val changed = eventsOf(mapped).single() as SseEvent.SessionAgentPresetChanged
        assertEquals("s9", changed.sessionId)
        assertEquals("cordis", changed.agentPreset)
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
            "compaction/summary" to DshIgnoreReason.COMPACTION,
            "compaction/prune" to DshIgnoreReason.COMPACTION,
            "subagent/descriptor" to DshIgnoreReason.SUBAGENT_DESCRIPTOR,
        )
        cases.forEach { (type, reason) ->
            assertEquals("type=$type", listOf(DshMappedEvent.Ignored(reason)), DshEventMapper.mapSessionEvent("s9", sessionEvent(type)))
        }
    }

    @Test
    fun `protocol noise types are ignored without becoming unignorable`() {
        // 高频伴生事件（§1.7 实测分布）：不进目录会让几乎所有真实会话拒绝重建
        val noise = listOf(
            // 三 knob（sandbox/mode、approval/policy、permission/preset）已映射为
            // SessionPermissionChanged，不在噪声目录（见下方专门断言）。
            "plan/mode",
            "agent/inbox/spliced", "step/end", "llm/retry", "llm/retry-started",
            "command/run", "command/done", "request/header", "request/context",
            "session/end-seed", "tool/code-dispatch", "tool/code-dispatch-start",
            "approval/asked", "approval/decided", "web/deepseek-search-llm-request",
            "schedule/change", "feedback/record",
            // E2E 回归（2026-08-31）：llm/failover 曾致整会话拒绝重建；known-49 插件域收尾
            "llm/failover", "session/title-llm-request",
            "hook/invoked", "hook/result",
            "team/task", "team/member", "team/message/delivered", "team/message/queued",
            "tool-workflow/run-start", "tool-workflow/agent-start",
            "tool-workflow/agent-end", "tool-workflow/run-end",
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

    // ============ goal/change 与投影键（backlog #286） ============

    @Test
    fun `goal-change event maps to session goal projection whole-value`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            sessionEvent(
                "goal/change",
                """{"kind":"goal/change","version":1,"operation":"create","goal":{"id":"goal-1","revision":2,"objective":"build the ring","phase":"active","maxGoalRounds":5},"roundsStarted":1,"createdAt":1700000000000,"updatedAt":1700000001000}""",
            ),
        )
        val event = eventsOf(mapped).single() as SseEvent.SessionGoalChanged
        assertEquals("s1", event.sessionId)
        val p = event.goal!!
        assertEquals("goal-1", p.goal.id)
        assertEquals(2L, p.goal.revision)
        assertEquals("build the ring", p.goal.objective)
        assertEquals("active", p.goal.phase)
        assertNull(p.goal.blockedReason)
        assertEquals(5L, p.goal.maxGoalRounds)
        assertEquals(1L, p.roundsStarted)
    }

    @Test
    fun `goal-change blocked carries inline reason`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            sessionEvent(
                "goal/change",
                """{"kind":"goal/change","version":1,"operation":"block","goal":{"id":"goal-1","revision":3,"objective":"x","phase":"blocked","blockedReason":{"code":"goal-blocked-rounds","message":"exhausted goal rounds"},"maxGoalRounds":3},"roundsStarted":3,"createdAt":1,"updatedAt":2}""",
            ),
        )
        val event = eventsOf(mapped).single() as SseEvent.SessionGoalChanged
        assertEquals("blocked", event.goal!!.goal.phase)
        assertEquals("exhausted goal rounds", event.goal!!.goal.blockedReason!!.message)
        assertEquals("goal-blocked-rounds", event.goal!!.goal.blockedReason!!.code)
    }

    @Test
    fun `goal-change clear tombstone maps to null projection`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            sessionEvent(
                "goal/change",
                """{"kind":"goal/change","version":1,"operation":"clear","cleared":{"id":"goal-1","revision":4},"clearedAt":3}""",
            ),
        )
        val event = eventsOf(mapped).single() as SseEvent.SessionGoalChanged
        assertNull(event.goal)
    }

    @Test
    fun `projection key goal null value is not malformed`() {
        val payload = json.parseToJsonElement(
            """{"type":"session/projection","sessionId":"s1","key":"goal","value":null,"seq":7}""",
        ).jsonObject
        val event = eventsOf(DshEventMapper.mapFrame("session/projection", payload)).single()
            as SseEvent.SessionGoalChanged
        assertNull(event.goal)
    }

    @Test
    fun `projection keys contextPressure breakdown and sessionStats map`() {
        val pressure = DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                """{"type":"session/projection","sessionId":"s1","key":"contextPressure","value":{"pressureTokens":124658,"projectedTokens":125148,"contextWindow":1000000},"seq":8}""",
            ).jsonObject,
        )
        val p = (eventsOf(pressure).single() as SseEvent.SessionContextPressureChanged).pressure
        assertEquals(124658L, p.pressureTokens)
        assertEquals(125148L, p.projectedTokens)
        assertEquals(1000000L, p.contextWindow)

        val bd = DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                """{"type":"session/projection","sessionId":"s1","key":"contextBreakdown","value":{"systemTokens":9408,"toolsTokens":240,"messageTokens":99722},"seq":9}""",
            ).jsonObject,
        )
        val b = (eventsOf(bd).single() as SseEvent.SessionContextBreakdownChanged).breakdown
        assertEquals(9408L, b.systemTokens)
        assertEquals(240L, b.toolsTokens)
        assertEquals(99722L, b.messageTokens)

        val st = DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                """{"type":"session/projection","sessionId":"s1","key":"sessionStats","value":{"turns":1,"steps":60,"llmMs":304019,"toolMs":3514,"ttftMs":194905,"ttftSteps":61,"decodeMs":109114,"decodeTokens":17112},"seq":10}""",
            ).jsonObject,
        )
        val s = (eventsOf(st).single() as SseEvent.SessionStatsChanged).stats
        assertEquals(1L, s.turns)
        assertEquals(60L, s.steps)
        assertEquals(304019L, s.llmMs)
        assertEquals(17112L, s.decodeTokens)
    }
}
