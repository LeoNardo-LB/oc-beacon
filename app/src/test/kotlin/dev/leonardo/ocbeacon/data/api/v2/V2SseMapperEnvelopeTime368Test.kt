package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #368（2026-09-10 wire 实证）：V2 SSE 信封顶层服务器时刻 created 穿入 mapper
 * 后——消息/part 时间戳优先服务器钟域，缺席回退设备钟（不劣化）。
 * 实测帧形状：{id, created:1788920418637, type, location, data, durable:{seq}}。
 */
class V2SseMapperEnvelopeTime368Test {

    private val json = Json { ignoreUnknownKeys = true }
    private fun props(t: String) = json.parseToJsonElement(t).jsonObject

    @Test
    fun `step started uses envelope time for created`() {
        val event = V2SseMapper.map(
            "session.step.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_a","agent":"build"}"""),
            envelopeTimeMs = 1788920418637,
        )
        val assistant = (event as SseEvent.MessageUpdated).info as Message.Assistant
        assertEquals(1788920418637L, assistant.time.created)
    }

    @Test
    fun `step ended stamps server completion`() {
        val event = V2SseMapper.map(
            "session.step.ended",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_a","finish":"stop"}"""),
            envelopeTimeMs = 1788920418669,
        )
        val assistant = (event as SseEvent.MessageUpdated).info as Message.Assistant
        assertEquals(1788920418669L, assistant.time.completed)
    }

    @Test
    fun `null envelope falls back to device clock`() {
        val before = System.currentTimeMillis()
        val event = V2SseMapper.map(
            "session.step.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_b"}"""),
            envelopeTimeMs = null,
        )
        val assistant = (event as SseEvent.MessageUpdated).info as Message.Assistant
        assertTrue(assistant.time.created >= before)
    }

    @Test
    fun `part times use envelope clock`() {
        val started = V2SseMapper.map(
            "session.text.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_c","ordinal":0}"""),
            envelopeTimeMs = 1000L,
        ) as SseEvent.MessagePartUpdated
        assertEquals(1000L, (started.part as Part.Text).time!!.start)
        val ended = V2SseMapper.map(
            "session.text.ended",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_c","ordinal":0,"text":"pong"}"""),
            envelopeTimeMs = 2500L,
        ) as SseEvent.MessagePartUpdated
        assertEquals(2500L, (ended.part as Part.Text).time!!.end)
    }

    @Test
    fun `legacy two-arg overload still works`() {
        val event = V2SseMapper.map(
            "session.step.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_d"}"""),
        )
        assertNotNull(event)
    }
}
