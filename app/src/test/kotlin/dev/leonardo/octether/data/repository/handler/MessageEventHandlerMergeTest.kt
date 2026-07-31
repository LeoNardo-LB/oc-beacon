package dev.leonardo.octether.data.repository.handler

import dev.leonardo.octether.domain.model.*
import dev.leonardo.octether.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the SSE/REST merge strategy in [MessageEventHandler].
 * Verifies that streaming content accumulated via SSE is preserved
 * when REST data arrives with stale or empty text.
 */
class MessageEventHandlerMergeTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    // ============ Test 1: setMessages preserves SSE streaming text over REST empty text ============

    @Test
    fun `setMessages preserves SSE streaming text over REST empty text`() {
        // SSE: build up "Hello World" via MessageUpdated → PartUpdated → 2x PartDelta
        val msg = Message.Assistant(
            id = "msg-1",
            sessionId = "s1",
            parentId = "parent-1",
            time = TimeInfo(created = 1000L)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(msg))

        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "msg-1", partId = "p1",
            field = "text", delta = "Hello"
        ))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "msg-1", partId = "p1",
            field = "text", delta = " World"
        ))
        handler.forceFlushDeltas()

        // Verify SSE accumulation worked
        assertEquals("Hello World", (handler.parts.value["msg-1"]!![0] as Part.Text).text)

        // REST: setMessages with empty text (server snapshot hasn't caught up)
        val restMsg = msg.copy()
        val restPart = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "")
        handler.setMessages("s1", listOf(MessageWithParts(restMsg, listOf(restPart))))

        // mergePartsList should preserve longer text
        val mergedPart = handler.parts.value["msg-1"]!![0] as Part.Text
        assertEquals("Hello World", mergedPart.text)
    }

    // ============ Test 2: setMessages preserves SSE incomplete message metadata ============

    @Test
    fun `setMessages preserves SSE incomplete message metadata`() {
        // SSE: Assistant message with completed=null (still streaming)
        val sseMsg = Message.Assistant(
            id = "msg-1",
            sessionId = "s1",
            parentId = "parent-1",
            time = TimeInfo(created = 1000L, completed = null)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(sseMsg))

        // REST: same message with completed=2000L (server knows it's done)
        val restMsg = Message.Assistant(
            id = "msg-1",
            sessionId = "s1",
            parentId = "parent-1",
            time = TimeInfo(created = 1000L, completed = 2000L)
        )
        handler.setMessages("s1", listOf(MessageWithParts(restMsg, emptyList())))

        // mergeMessageMeta should merge completed time from REST into SSE version
        val merged = handler.messages.value["s1"]!![0] as Message.Assistant
        assertEquals(2000L, merged.time.completed)
    }

    // ============ Test 3: setMessages does not clear parts for messages not in REST response ============

    @Test
    fun `setMessages does not clear parts for messages not in REST response`() {
        // SSE: two messages, each with parts
        val msg1 = Message.Assistant(
            id = "msg-1", sessionId = "s1", parentId = "p1",
            time = TimeInfo(created = 1000L)
        )
        val msg2 = Message.Assistant(
            id = "msg-2", sessionId = "s1", parentId = "p2",
            time = TimeInfo(created = 2000L)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(msg1))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(msg2))

        val part1 = Part.Text(id = "pa1", sessionId = "s1", messageId = "msg-1", text = "")
        val part2 = Part.Text(id = "pa2", sessionId = "s1", messageId = "msg-2", text = "")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part1))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part2))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "msg-1", partId = "pa1",
            field = "text", delta = "Text 1"
        ))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "msg-2", partId = "pa2",
            field = "text", delta = "Text 2"
        ))
        handler.forceFlushDeltas()

        // REST: only msg-1 (msg-2 is streaming and not in REST snapshot yet)
        handler.setMessages("s1", listOf(MessageWithParts(msg1, listOf(part1))))

        // msg-2 should still be in messages
        val messages = handler.messages.value["s1"]!!
        assertEquals(2, messages.size)
        assertEquals("msg-1", messages[0].id)
        assertEquals("msg-2", messages[1].id)

        // msg-2's parts should be preserved (current + merged keeps existing keys)
        val msg2Parts = handler.parts.value["msg-2"]
        assertNotNull("msg-2 parts should be preserved", msg2Parts)
        assertEquals(1, msg2Parts!!.size)
        assertEquals("Text 2", (msg2Parts[0] as Part.Text).text)
    }

    // ============ Test 4: handleMessagePartUpdated keeps longer existing text over shorter incoming ============

    @Test
    fun `handleMessagePartUpdated keeps longer existing text over shorter incoming text`() {
        // SSE: build up text via PartUpdated + PartDelta = "Accumulated SSE text"
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "Accumulated")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "msg-1", partId = "p1",
            field = "text", delta = " SSE text"
        ))
        handler.forceFlushDeltas()

        assertEquals("Accumulated SSE text", (handler.parts.value["msg-1"]!![0] as Part.Text).text)

        // SSE: incoming PartUpdated with shorter text "Short"
        val shortPart = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "Short")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(shortPart))

        // mergePart should keep longer existing text
        val result = handler.parts.value["msg-1"]!![0] as Part.Text
        assertEquals("Accumulated SSE text", result.text)
    }
}
