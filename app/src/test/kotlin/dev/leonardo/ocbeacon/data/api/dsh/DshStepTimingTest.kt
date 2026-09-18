package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #411：DSH 逐轮 timing 派生（step/start → 首 token（isTokenDelta 规则）→
 * assistant/message 结算）。权威语义 = dsh-session-stats openStep 折叠 +
 * dsh-llm assistant-stream.js isTokenDelta（app 侧复刻见 DshEventMapper.firstTokenTime）。
 *
 * DshEventMapper 为 object（stepStartTimes 跨用例共享）——各用例用不同
 * sessionId 隔离 step 命名空间。
 */
class DshStepTimingTest {

    private val json = Json

    private fun assistantOf(sessionId: String, vararg frames: String): Message.Assistant =
        frames.flatMap { frame ->
            DshEventMapper.mapSessionEvent(sessionId, json.parseToJsonElement(frame).jsonObject)
                .filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }
        }
            .filterIsInstance<SseEvent.MessageUpdated>()
            .map { it.info }
            .filterIsInstance<Message.Assistant>()
            .first()

    @Test
    fun `ttft and decode derive from step start and first token`() {
        val msg = assistantOf(
            "t1",
            """{"type":"step/start","seq":1,"time":1000,"data":{"turn":2,"step":5}}""",
            """{"type":"assistant/message","seq":2,"time":4000,"data":{"turn":2,"step":5,
               "message":{"id":"m1","role":"assistant","content":[]},
               "stream":[{"type":"chunk","time":1500,"chunk":{"type":"text-delta","text":"你"}},
                         {"type":"chunk","time":3800,"chunk":{"type":"usage","usage":{"inputTokens":10,"outputTokens":120}}}],
               "usage":{"inputTokens":10,"outputTokens":120}}}""",
        )
        assertEquals(500L, msg.ttftMs)
        assertEquals(2500L, msg.decodeMs)
        assertEquals(120L, msg.decodeTokens)
    }

    @Test
    fun `missing step start leaves ttft null but decode present`() {
        val msg = assistantOf(
            "t2",
            """{"type":"assistant/message","seq":2,"time":4000,"data":{"turn":2,"step":5,
               "message":{"id":"m1","role":"assistant","content":[]},
               "stream":[{"type":"chunk","time":1500,"chunk":{"type":"text-delta","text":"你"}}],
               "usage":{"inputTokens":10,"outputTokens":120}}}""",
        )
        assertNull(msg.ttftMs)
        assertEquals(2500L, msg.decodeMs)
        assertEquals(120L, msg.decodeTokens)
    }

    @Test
    fun `usage only stream has no first token`() {
        val msg = assistantOf(
            "t3",
            """{"type":"step/start","seq":1,"time":1000,"data":{"turn":1,"step":1}}""",
            """{"type":"assistant/message","seq":2,"time":4000,"data":{"turn":1,"step":1,
               "message":{"id":"m2","role":"assistant","content":[]},
               "stream":[{"type":"chunk","time":3800,"chunk":{"type":"usage","usage":{"inputTokens":10,"outputTokens":120}}}],
               "usage":{"inputTokens":10,"outputTokens":120}}}""",
        )
        assertNull(msg.ttftMs)
        assertNull(msg.decodeMs)
        assertEquals(120L, msg.decodeTokens)
    }

    @Test
    fun `turn end clears pending step starts`() {
        val msg = assistantOf(
            "t4",
            """{"type":"step/start","seq":1,"time":1000,"data":{"turn":3,"step":7}}""",
            """{"type":"turn/end","seq":2,"time":2000,"data":{"turn":3}}""",
            """{"type":"assistant/message","seq":3,"time":4000,"data":{"turn":3,"step":7,
               "message":{"id":"m3","role":"assistant","content":[]},
               "stream":[{"type":"chunk","time":1500,"chunk":{"type":"reasoning-delta","text":"想"}}],
               "usage":{"inputTokens":1,"outputTokens":2}}}""",
        )
        assertNull(msg.ttftMs)
        assertEquals(2500L, msg.decodeMs)
    }
}
