package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshHistoryFolder 历史折叠测试（backlog #275 组件 B；设计文档 §1.7 fold 范围决策）。
 *
 * 黄金样本 app/src/test/resources/dsh/history-sample.jsonl 全量驱动：
 * - header 行（type=session）跳过并供 sessionId；
 * - chunk 族（assistant/chunk 单块 + seq0 打包行）不进历史 fold——整装由
 *   assistant/message 承载（§1.7：历史以整装事件族为主）；
 * - 保序；lastSeq 覆盖活事件 seq 与打包行 seq0（服务端视角已应用水位）；
 * - team/task（未知类型）进 unknownUnignorable → 拒绝重建判据（§5 信封规则）。
 */
class DshHistoryFolderTest {

    private val json = Json

    private fun resourceText(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes().decodeToString()

    private fun goldenRows(): List<JsonObject> =
        resourceText("dsh/history-sample.jsonl").lineSequence().filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }.toList()

    // ============ 黄金样本全量驱动 ============

    @Test
    fun `golden sample folds to exact ordered sse event sequence`() {
        val result = DshHistoryFolder.fold(goldenRows())
        val events = result.sseEvents
        assertEquals(17, events.size)
        // 顺序即历史行序（保序）：三 knob（sandbox/approval/permission）→ SessionPermissionChanged
        // （权限预设切换器事件回显，不再 Ignored），随后 turn/start → busy；user/message 整装
        assertEquals(
            SseEvent.SessionPermissionChanged("fixture-0001", sandboxMode = "workspace-write"),
            events[0],
        )
        assertEquals(
            SseEvent.SessionPermissionChanged("fixture-0001", approvalPolicy = "never"),
            events[1],
        )
        assertEquals(
            SseEvent.SessionPermissionChanged("fixture-0001", preset = "workspace-write"),
            events[2],
        )
        assertEquals(SseEvent.SessionStatus("fixture-0001", SessionStatus.Busy), events[3])
        val user = events[4] as SseEvent.MessageUpdated
        assertEquals("seq-5", user.info.id)
        assertEquals("fixture-0001", user.info.sessionId)
        assertEquals(1788109000011L, user.info.time.created)
        assertEquals("fixture user prompt", ((events[5] as SseEvent.MessagePartUpdated).part as Part.Text).text)
        // step/start → busy（chunk 行 9-12 被跳过后紧邻 tool/call）
        assertEquals(SseEvent.SessionStatus("fixture-0001", SessionStatus.Busy), events[6])
        // tool/call → 宿主消息 + Pending 工具卡
        assertEquals("dsh-call-call_fixture_1", (events[7] as SseEvent.MessageUpdated).info.id)
        val pending = (events[8] as SseEvent.MessagePartUpdated).part as Part.Tool
        assertEquals("call_fixture_1", pending.id)
        assertTrue(pending.state is dev.leonardo.ocbeacon.domain.model.ToolState.Pending)
        // tool/result → Completed 工具卡（同 callId 汇合）
        val completed = (events[9] as SseEvent.MessagePartUpdated).part as Part.Tool
        assertEquals("call_fixture_1", completed.id)
        assertTrue(completed.state is dev.leonardo.ocbeacon.domain.model.ToolState.Completed)
        // assistant/message 整装：流式桥拆除 + 消息 + reasoning/text part
        assertEquals(SseEvent.MessageRemoved("fixture-0001", "dsh-t1s1"), events[10])
        val assistant = (events[11] as SseEvent.MessageUpdated).info as Message.Assistant
        assertEquals("seq-13", assistant.id)
        assertEquals(1788109000019L, assistant.time.completed)
        assertEquals("thinking...", ((events[12] as SseEvent.MessagePartUpdated).part as Part.Reasoning).text)
        assertEquals("answer", ((events[13] as SseEvent.MessagePartUpdated).part as Part.Text).text)
        // turn/end → idle；todo/title
        assertEquals(SseEvent.SessionIdle("fixture-0001", 1788109000021), events[14])  // #294：time 透传
        assertTrue(events[15] is SseEvent.TodoUpdated)
        val updated = events[16] as SseEvent.SessionUpdated
        assertEquals("fixture session", updated.info.title)
    }

    @Test
    fun `golden sample yields session idle exactly once`() {
        val idles = DshHistoryFolder.fold(goldenRows()).sseEvents.filterIsInstance<SseEvent.SessionIdle>()
        assertEquals(listOf(SseEvent.SessionIdle("fixture-0001", 1788109000021)), idles)
    }

    @Test
    fun `golden sample chunk family is skipped entirely`() {
        val events = DshHistoryFolder.fold(goldenRows()).sseEvents
        // 4 行 assistant/chunk（block-start/delta/block-end/text-delta）+ 1 行打包
        // reasoning-chunks 均不产生事件（§1.7：chunk 族不进历史 fold）
        assertTrue(events.none { it is SseEvent.MessagePartDelta })
        assertTrue(events.none { it is SseEvent.MessagePartUpdated && it.part.messageId == "dsh-t1s1" })
    }

    @Test
    fun `golden sample collects only team task as unknown unignorable`() {
        val result = DshHistoryFolder.fold(goldenRows())
        assertEquals(listOf("future/plugin-unknown"), result.unknownUnignorable)
        assertTrue(result.refusedRebuild) // §5：未知类型无 ignorable 必须拒绝重建
    }

    @Test
    fun `golden sample last seq spans live seq and packed seq0 and header supplies session id`() {
        val result = DshHistoryFolder.fold(goldenRows())
        assertEquals(19L, result.lastSeq) // seq 19（future/plugin-unknown）> seq0 18（打包行）
        assertEquals("fixture-0001", result.sessionId)
    }

    // ============ 行形态边界 ============

    @Test
    fun `packed rows advance last seq but emit no events`() {
        val rows = listOf(
            json.parseToJsonElement(
                """{"type":"session","version":0,"id":"p-1","createdAt":1,"cwd":"/w"}"""
            ).jsonObject,
            json.parseToJsonElement(
                """{"type":"text-chunks","seq0":99,"time0":2,"data":{"turn":1,"step":1,"index":0,"dt":[5],"texts":["x"]}}"""
            ).jsonObject,
        )
        val result = DshHistoryFolder.fold(rows)
        assertEquals(0, result.sseEvents.size)
        assertEquals(0, result.unknownUnignorable.size)
        assertEquals(99L, result.lastSeq) // 服务端已应用水位含打包行——否则对账误判缺口
        assertEquals("p-1", result.sessionId)
    }

    @Test
    fun `history entry wrapper rows are unwrapped`() {
        // session.history RPC 返回 HistoryEntry {event, view?}——解包后同路径 fold
        val rows = listOf(
            json.parseToJsonElement("""{"type":"session","version":0,"id":"w-1","createdAt":1,"cwd":"/w"}""").jsonObject,
            json.parseToJsonElement(
                """{"event":{"type":"turn/end","seq":7,"time":9,"data":{"turn":1,"reason":{"kind":"completed"}}},"view":null}"""
            ).jsonObject,
        )
        val result = DshHistoryFolder.fold(rows)
        assertEquals(listOf<SseEvent>(SseEvent.SessionIdle("w-1", 9)), result.sseEvents)  // #294
        assertEquals(7L, result.lastSeq)
    }

    @Test
    fun `explicit session id parameter wins over header`() {
        val rows = listOf(
            json.parseToJsonElement("""{"type":"session","version":0,"id":"header-id","createdAt":1,"cwd":"/w"}""").jsonObject,
            json.parseToJsonElement("""{"type":"turn/end","seq":1,"time":2,"data":{"turn":1}}""").jsonObject,
        )
        assertEquals(SseEvent.SessionIdle("param-id", 2), DshHistoryFolder.fold(rows, sessionId = "param-id").sseEvents.single())  // #294
    }

    @Test
    fun `empty input folds to empty result with zero last seq`() {
        val result = DshHistoryFolder.fold(emptyList())
        assertEquals(0, result.sseEvents.size)
        assertEquals(0L, result.lastSeq)
        assertEquals("", result.sessionId)
        assertEquals(0, result.unknownUnignorable.size)
    }

    @Test
    fun `fold lines tolerates blank and malformed lines`() {
        val text = listOf(
            """{"type":"session","version":0,"id":"l-1","createdAt":1,"cwd":"/w"}""",
            "}}garbage{{",
            "",
            """{"type":"turn/end","seq":3,"time":4,"data":{"turn":1}}""",
        ).joinToString("\n")
        val result = DshHistoryFolder.foldLines(text)
        assertEquals(listOf<SseEvent>(SseEvent.SessionIdle("l-1", 4)), result.sseEvents)  // #294
        assertEquals(3L, result.lastSeq)
    }

    @Test
    fun `mux frame rows mixed into history fold as known ignorable not refused`() {
        // B.4：session/projection|jobs|queue、stream/error 是 WS 帧面——历史重放/翻页
        // 若出现这些 type 行，须折叠进已知可忽略集（不落 UNKNOWN_UNIGNORABLE 拒绝重建）。
        val rows = listOf(
            json.parseToJsonElement("""{"type":"session","version":0,"id":"m-1","createdAt":1,"cwd":"/w"}""").jsonObject,
            json.parseToJsonElement("""{"type":"session/projection","sessionId":"m-1","key":"tokenUsage","value":{"uncachedInputTokens":1},"seq":1}""").jsonObject,
            json.parseToJsonElement("""{"type":"session/jobs","sessionId":"m-1","jobs":[]}""").jsonObject,
            json.parseToJsonElement("""{"type":"session/queue","sessionId":"m-1","items":[]}""").jsonObject,
            json.parseToJsonElement("""{"type":"stream/error","error":{"code":"internal"}}""").jsonObject,
            json.parseToJsonElement("""{"type":"turn/end","seq":5,"time":6,"data":{"turn":1}}""").jsonObject,
        )
        val result = DshHistoryFolder.fold(rows)
        assertEquals(listOf<SseEvent>(SseEvent.SessionIdle("m-1", 6)), result.sseEvents)  // #294
        assertEquals(0, result.unknownUnignorable.size) // 帧型混入不拒绝重建
        assertEquals(false, result.refusedRebuild)
        assertEquals(5L, result.lastSeq) // 帧型行无 seq，lastSeq 只由活事件推进
    }

    @Test
    fun `unknown unignorable accumulates distinct types in order`() {
        val rows = listOf(
            json.parseToJsonElement("""{"type":"session","version":0,"id":"u-1","createdAt":1,"cwd":"/w"}""").jsonObject,
            json.parseToJsonElement("""{"type":"future/a","seq":1,"time":2,"data":{}}""").jsonObject,
            json.parseToJsonElement("""{"type":"hook/pre-exec","seq":2,"time":3,"data":{}}""").jsonObject,
            json.parseToJsonElement("""{"type":"team/task","seq":3,"time":4,"data":{}}""").jsonObject,
        )
        val result = DshHistoryFolder.fold(rows)
        // team/task 已收编 PLUGIN_DOMAIN（2026-08-31）——不再进 unknownUnignorable
        assertEquals(listOf("future/a", "hook/pre-exec"), result.unknownUnignorable)
    }
}
