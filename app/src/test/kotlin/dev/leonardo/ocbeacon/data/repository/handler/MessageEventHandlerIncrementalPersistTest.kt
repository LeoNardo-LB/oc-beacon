package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #97（H-6）：SSE 增量落盘——flush 后 delta 按 part 追加（不丢文本），
 * 且增量写与全量写（handleMessageUpdated）路径一致。
 */
class MessageEventHandlerIncrementalPersistTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    private fun assistantMsg(id: String) = Message.Assistant(
        id = id,
        sessionId = "s1",
        parentId = "",
        time = TimeInfo(created = 1000L)
    )

    @Test
    fun `incremental flush accumulates deltas across batches`() {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(assistantMsg("m1")))
        // 第一批：2 个 delta（同一 part 聚合）
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1", field = "text", delta = "Hello"
        ))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1", field = "text", delta = " world"
        ))
        handler.forceFlushDeltas()

        // 第二批：追加
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1", field = "text", delta = "!"
        ))
        handler.forceFlushDeltas()

        val part = handler.parts.value["m1"]!!.first() as Part.Text
        assertEquals("Hello world!", part.text)
    }

    @Test
    fun `message updated after deltas keeps accumulated text`() {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(assistantMsg("m1")))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(
            Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "")
        ))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1", field = "text", delta = "流式"
        ))
        handler.forceFlushDeltas()

        // 消息更新（completed）后文本保留
        val updated = assistantMsg("m1").copy(
            time = TimeInfo(created = 1000L, completed = 2000L)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(updated))

        val part = handler.parts.value["m1"]!!.first() as Part.Text
        assertEquals("流式", part.text)
    }
}