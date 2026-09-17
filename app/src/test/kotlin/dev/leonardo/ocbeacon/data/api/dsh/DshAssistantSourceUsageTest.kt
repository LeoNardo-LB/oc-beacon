package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (2026-09-12 消息层扁平化) 批1 数据层：(a) DSH 模型路由 + (e) usage 全桶。
 *
 * 权威形状来自本机 DSH 0.1.5 实况归档（session.v3.jsonl.zstd）与
 * dsh-client-ui-chat normalizeUsage（chat.js:6931-6964）：
 * - message.source = {kind:"model", provider, model}
 * - usage = {inputTokens, outputTokens, totalTokens?, cacheReadTokens?, cacheWriteTokens?, reasoningTokens?}
 */
class DshAssistantSourceUsageTest {

    private val json = Json

    private fun envelope(body: String): JsonObject =
        json.parseToJsonElement(body).jsonObject

    private fun eventsOf(sessionId: String, body: String): List<SseEvent> =
        DshEventMapper.mapSessionEvent(sessionId, envelope(body))
            .filterIsInstance<DshMappedEvent.Sse>()
            .map { it.event }

    private fun assistantMessageOf(sessionId: String, body: String): Message.Assistant =
        eventsOf(sessionId, body)
            .filterIsInstance<SseEvent.MessageUpdated>()
            .map { it.info }
            .filterIsInstance<Message.Assistant>()
            .first()

    // ---- (a) 模型路由 --------------------------------------------------------

    @Test
    fun `assistant message reads provider and model from message source`() {
        val msg = assistantMessageOf(
            "s1",
            """{"type":"assistant/message","seq":100,"time":1788109999000,"data":{"turn":1,"step":1,
               "message":{"id":"m1","role":"assistant",
                 "source":{"kind":"model","provider":"opencode-go","model":"deepseek-flash"},
                 "content":[{"type":"text","text":"hi"}]},
               "usage":{"inputTokens":100,"outputTokens":50}}}""",
        )
        assertEquals("deepseek-flash", msg.modelId)
        assertEquals("opencode-go", msg.providerId)
    }

    @Test
    fun `assistant message without source keeps model null`() {
        val msg = assistantMessageOf(
            "s1",
            """{"type":"assistant/message","seq":100,"time":1,"data":{"turn":1,"step":1,
               "message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"x"}]},
               "usage":{"inputTokens":1,"outputTokens":1}}}""",
        )
        assertNull(msg.modelId)
        assertNull(msg.providerId)
    }

    // ---- (e) usage 全桶 ------------------------------------------------------

    @Test
    fun `assistant message usage maps every bucket including cache and reasoning`() {
        val msg = assistantMessageOf(
            "s1",
            """{"type":"assistant/message","seq":100,"time":1,"data":{"turn":1,"step":1,
               "message":{"id":"m1","role":"assistant","content":[]},
               "usage":{"inputTokens":8343,"outputTokens":554,"totalTokens":17473,
                        "cacheReadTokens":8576,"cacheWriteTokens":12,"reasoningTokens":21}}}""",
        )
        val t = msg.tokens
        assertNotNull(t)
        assertEquals(8343, t!!.input)
        assertEquals(554, t.output)
        assertEquals(17473, t.total)
        assertEquals(21, t.reasoning)
        assertEquals(8576, t.cache.read)
        assertEquals(12, t.cache.write)
    }

    @Test
    fun `assistant message usage derives total when server omits it`() {
        val msg = assistantMessageOf(
            "s1",
            """{"type":"assistant/message","seq":100,"time":1,"data":{"turn":1,"step":1,
               "message":{"id":"m1","role":"assistant","content":[]},
               "usage":{"inputTokens":10,"outputTokens":5,"cacheReadTokens":3,"cacheWriteTokens":2}}}""",
        )
        assertEquals(20, msg.tokens!!.total)
    }

    @Test
    fun `chunk usage updates the streaming host tokens`() {
        val events = eventsOf(
            "s1",
            """{"type":"assistant/chunk","seq":101,"time":1,"data":{"turn":3,"step":2,
               "chunk":{"type":"usage","usage":{"inputTokens":1666,"outputTokens":575,
                        "totalTokens":41601,"cacheReadTokens":39360}}}}""",
        )
        val updated = events.filterIsInstance<SseEvent.MessageUpdated>()
            .map { it.info }
            .filterIsInstance<Message.Assistant>()
            .single()
        assertEquals(DshEventMapper.streamingMessageId(3, 2), updated.id)
        val t = updated.tokens!!
        assertEquals(1666, t.input)
        assertEquals(575, t.output)
        assertEquals(41601, t.total)
        assertEquals(39360, t.cache.read)
    }

    @Test
    fun `chunk usage without input and output is ignored`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                """{"type":"assistant/chunk","seq":101,"time":1,"data":{"turn":1,"step":1,
                   "chunk":{"type":"usage","usage":{"totalTokens":5}}}}""",
            ),
        )
        assertTrue(mapped.all { it !is DshMappedEvent.Sse })
        assertTrue(mapped.filterIsInstance<DshMappedEvent.Ignored>().any { it.reason == DshIgnoreReason.CHUNK_USAGE })
    }
}
