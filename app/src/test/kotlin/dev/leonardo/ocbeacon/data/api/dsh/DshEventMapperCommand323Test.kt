package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #323 斜杠命令执行反馈行——mapper 两分支（command/run|done → SseEvent）。
 *
 * 契约（dsh-commands index.js:302-326）：
 * - command/run {commandId, name, args?, source}——handler 前直追加；
 * - command/done {commandId, kind(success|error|…), text?, sourceEventSeq?}——结算后；
 * - commandId 配对；直追加无轮包裹（log-only durable——历史重放同路径渲染）。
 */
class DshEventMapperCommand323Test {

    private val json = Json

    private fun envelope(type: String, data: String, seq: Long = 7, time: Long = 1788109999000): JsonObject =
        json.parseToJsonElement(
            """{"type":"$type","seq":$seq,"time":$time,"data":$data}"""
        ).jsonObject

    @Test
    fun `command run maps to CommandRunStarted with full payload`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("command/run", """{"commandId":"cmd-1","name":"compact","args":"--keep 5","source":{"kind":"user"}}"""),
        )
        assertEquals(
            listOf(
                DshMappedEvent.Sse(
                    SseEvent.CommandRunStarted(
                        sessionId = "s1",
                        commandId = "cmd-1",
                        name = "compact",
                        args = "--keep 5",
                        source = "user",
                        seq = 7,
                        time = 1788109999000,
                    )
                )
            ),
            mapped,
        )
    }

    @Test
    fun `command run without args and source maps null optionals`() {
        // recordInput=false 的命令不携带 args；source 缺席防御
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("command/run", """{"commandId":"cmd-2","name":"share"}""", seq = 8),
        )
        val run = mapped.single() as DshMappedEvent.Sse
        assertEquals(
            SseEvent.CommandRunStarted(sessionId = "s1", commandId = "cmd-2", name = "share", seq = 8, time = 1788109999000),
            run.event,
        )
    }

    @Test
    fun `command done maps to CommandDone with kind text and sourceEventSeq`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                "command/done",
                """{"commandId":"cmd-1","kind":"success","text":"compacted","sourceEventSeq":42}""",
                seq = 9,
                time = 1788110000000,
            ),
        )
        assertEquals(
            listOf(
                DshMappedEvent.Sse(
                    SseEvent.CommandDone(
                        sessionId = "s1",
                        commandId = "cmd-1",
                        kind = "success",
                        text = "compacted",
                        sourceEventSeq = 42,
                        seq = 9,
                        time = 1788110000000,
                    )
                )
            ),
            mapped,
        )
    }

    @Test
    fun `command done error without text maps null optionals`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("command/done", """{"commandId":"cmd-3","kind":"error"}""", seq = 10),
        )
        val done = mapped.single() as DshMappedEvent.Sse
        assertEquals(
            SseEvent.CommandDone(sessionId = "s1", commandId = "cmd-3", kind = "error", seq = 10, time = 1788109999000),
            done.event,
        )
    }

    @Test
    fun `command run or done missing commandId is malformed not unignorable`() {
        // #327 历史行防御纪律：畸形降级必须具名（MALFORMED），绝不能落
        // UNKNOWN_UNIGNORABLE——否则整会话历史折叠拒绝重建。
        listOf("command/run" to "{\"name\":\"compact\"}", "command/done" to "{\"kind\":\"success\"}").forEach { (type, data) ->
            val mapped = DshEventMapper.mapSessionEvent("s1", envelope(type, data))
            assertEquals("type=$type", listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED)), mapped)
        }
    }

    @Test
    fun `history fold renders command rows as durable events without refusing rebuild`() {
        // 历史重放同路径：command/run|done 是真实转录事件（非「历史行词汇忽略」），
        // 折叠产出事件且不触发拒绝重建（#327 历史行词汇收编判据）。
        val rows = listOf(
            """{"type":"user/message","seq":5,"time":100,"data":{"content":[{"type":"text","text":"hi"}]}}""",
            """{"type":"command/run","seq":6,"time":200,"data":{"commandId":"cmd-9","name":"compact","args":"","source":{"kind":"user"}}}""",
            """{"type":"command/done","seq":7,"time":300,"data":{"commandId":"cmd-9","kind":"success"}}""",
        ).map { json.parseToJsonElement(it).jsonObject }
        val result = DshHistoryFolder.fold(rows, "s1")
        assertFalse("command 行不得触发拒绝重建", result.refusedRebuild)
        val kinds = result.sseEvents.map { it::class.simpleName }
        assertTrue("折叠须产出 run/done 事件（durable 渲染）: $kinds", kinds.containsAll(listOf("CommandRunStarted", "CommandDone")))
        // 保序：run 先于 done（seq 升序——列表插入序键）
        assertTrue(
            result.sseEvents.indexOfFirst { it is SseEvent.CommandRunStarted } <
                result.sseEvents.indexOfFirst { it is SseEvent.CommandDone }
        )
    }
}
