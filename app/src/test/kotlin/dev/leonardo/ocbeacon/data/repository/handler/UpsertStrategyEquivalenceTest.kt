package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 验证 [MessageEventHandler.upsertMessages] 三策略与原三方法（setMessages/
 * mergeMessages/replaceMessages）的输出完全等价。
 *
 * 方法：在两个 handler 实例上用相同 fixture 分别调用旧方法与新策略，
 * 断言 messages 与 parts 的 StateFlow 快照逐字段相等。
 */
class UpsertStrategyEquivalenceTest {

    private lateinit var legacy: MessageEventHandler
    private lateinit var modern: MessageEventHandler

    @Before
    fun setup() {
        legacy = MessageEventHandler()
        modern = MessageEventHandler()
    }

    /** 在两个 handler 上播种相同的 SSE 累积状态。 */
    private fun seedSseState(h: MessageEventHandler) {
        val sseMsg = Message.Assistant(
            id = "msg-1", sessionId = "s1", parentId = "p1",
            time = TimeInfo(created = 1000L, completed = null)
        )
        h.handleMessageUpdated(SseEvent.MessageUpdated(sseMsg))
        h.handleMessagePartUpdated(SseEvent.MessagePartUpdated(
            Part.Text(id = "pa1", sessionId = "s1", messageId = "msg-1", text = "Hello World from SSE")
        ))
    }

    private val restMessage get() = Message.Assistant(
        id = "msg-1", sessionId = "s1", parentId = "p1",
        time = TimeInfo(created = 1000L, completed = 2000L)
    )
    private val restPart get() = Part.Text(
        id = "pa1", sessionId = "s1", messageId = "msg-1", text = "Hello"
    )
    private val restPayload get() = listOf(MessageWithParts(restMessage, listOf(restPart)))

    // ============ SSE_PRIORITY == setMessages ============

    @Test
    fun `upsertMessages SSE_PRIORITY equals setMessages`() {
        seedSseState(legacy); seedSseState(modern)

        legacy.setMessages("s1", restPayload)
        modern.upsertMessages("s1", restPayload, MergeStrategy.SSE_PRIORITY)

        assertEquals(legacy.messages.value, modern.messages.value)
        assertEquals(legacy.parts.value, modern.parts.value)
    }

    @Test
    fun `SSE_PRIORITY preserves SSE streaming text over shorter REST snapshot`() {
        seedSseState(modern)
        modern.upsertMessages("s1", restPayload, MergeStrategy.SSE_PRIORITY)

        val part = modern.parts.value["msg-1"]!![0] as Part.Text
        assertEquals("Hello World from SSE", part.text)
    }

    @Test
    fun `SSE_PRIORITY merges REST completed time into SSE incomplete message`() {
        seedSseState(modern)
        modern.upsertMessages("s1", restPayload, MergeStrategy.SSE_PRIORITY)

        val msg = modern.messages.value["s1"]!![0] as Message.Assistant
        assertEquals(2000L, msg.time.completed)
    }

    // ============ REST_AUTHORITY == replaceMessages ============

    @Test
    fun `upsertMessages REST_AUTHORITY equals replaceMessages`() {
        seedSseState(legacy); seedSseState(modern)

        legacy.replaceMessages("s1", restPayload)
        modern.upsertMessages("s1", restPayload, MergeStrategy.REST_AUTHORITY)

        assertEquals(legacy.messages.value, modern.messages.value)
        assertEquals(legacy.parts.value, modern.parts.value)
    }

    @Test
    fun `REST_AUTHORITY prefers incoming message info but preserves SSE-fresh longer parts`() {
        seedSseState(modern)
        modern.upsertMessages("s1", restPayload, MergeStrategy.REST_AUTHORITY)

        // REST message info 覆盖（completed 时间被 REST 设置）
        val msg = modern.messages.value["s1"]!![0] as Message.Assistant
        assertEquals(2000L, msg.time.completed)
        // 但 parts 仍保留更长的 SSE 文本（mergePartsList 更长文本胜出）
        val part = modern.parts.value["msg-1"]!![0] as Part.Text
        assertEquals("Hello World from SSE", part.text)
    }

    // ============ APPEND_ONLY == mergeMessages ============

    @Test
    fun `upsertMessages APPEND_ONLY equals mergeMessages`() {
        // APPEND_ONLY 的语义：仅补充缺失。先播种一个 existing 消息，
        // 再传入两条（一条 existing 一条新），验证 existing 不变 + 新增。
        val existingMsg = Message.Assistant(
            id = "msg-old", sessionId = "s1", parentId = "p0",
            time = TimeInfo(created = 500L, completed = 600L)
        )
        legacy.handleMessageUpdated(SseEvent.MessageUpdated(existingMsg))
        modern.handleMessageUpdated(SseEvent.MessageUpdated(existingMsg))
        // existing parts（不应被 APPEND_ONLY 覆盖）
        val existingPart = Part.Text(id = "po", sessionId = "s1", messageId = "msg-old", text = "old SSE text")
        legacy.handleMessagePartUpdated(SseEvent.MessagePartUpdated(existingPart))
        modern.handleMessagePartUpdated(SseEvent.MessagePartUpdated(existingPart))

        // incoming：existing 消息（短文本）+ 新消息
        val incomingOld = MessageWithParts(
            Message.Assistant(id = "msg-old", sessionId = "s1", parentId = "p0", time = TimeInfo(created = 500L, completed = null)),
            listOf(Part.Text(id = "po", sessionId = "s1", messageId = "msg-old", text = "old REST"))
        )
        val incomingNew = MessageWithParts(
            Message.User(id = "msg-new", sessionId = "s1", time = TimeInfo(created = 2000L)),
            listOf(Part.Text(id = "pn", sessionId = "s1", messageId = "msg-new", text = "new"))
        )

        legacy.mergeMessages("s1", listOf(incomingOld, incomingNew))
        modern.upsertMessages("s1", listOf(incomingOld, incomingNew), MergeStrategy.APPEND_ONLY)

        assertEquals(legacy.messages.value, modern.messages.value)
        assertEquals(legacy.parts.value, modern.parts.value)
    }

    @Test
    fun `APPEND_ONLY preserves existing SSE-fresh parts and does not merge`() {
        // mergeMessages 对 existing 的 parts 不做 mergePartsList，仅添加新 messageId 的 parts
        val existingMsg = Message.User(id = "msg-old", sessionId = "s1", time = TimeInfo(created = 500L))
        modern.handleMessageUpdated(SseEvent.MessageUpdated(existingMsg))
        val existingPart = Part.Text(id = "po", sessionId = "s1", messageId = "msg-old", text = "from SSE")
        modern.handleMessagePartUpdated(SseEvent.MessagePartUpdated(existingPart))

        val incomingOld = MessageWithParts(
            existingMsg,
            listOf(Part.Text(id = "po", sessionId = "s1", messageId = "msg-old", text = "from REST"))
        )
        modern.upsertMessages("s1", listOf(incomingOld), MergeStrategy.APPEND_ONLY)

        // existing parts 不被合并——保留 SSE 文本
        val part = modern.parts.value["msg-old"]!![0] as Part.Text
        assertEquals("from SSE", part.text)
    }
}