package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.assertTrue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * [MessageEventHandler] 中 SSE/REST 合并策略的测试。
 * 验证当 REST 数据以过期或空文本到达时，
 * 通过 SSE 累积的流式内容仍被保留。
 */
class MessageEventHandlerMergeTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    // ============ 测试 1：setMessages 保留 SSE 流式文本而非 REST 空文本 ============

    @Test
    fun `setMessages preserves SSE streaming text over REST empty text`() {
        // SSE：通过 MessageUpdated → PartUpdated → 2x PartDelta 累积 "Hello World"
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

        // 验证 SSE 累积生效
        assertEquals("Hello World", (handler.parts.value["msg-1"]!![0] as Part.Text).text)

        // REST：setMessages 携带空文本（服务器快照尚未跟上）
        val restMsg = msg.copy()
        val restPart = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "")
        handler.setMessages("s1", listOf(MessageWithParts(restMsg, listOf(restPart))))

        // mergePartsList 应保留更长的文本
        val mergedPart = handler.parts.value["msg-1"]!![0] as Part.Text
        assertEquals("Hello World", mergedPart.text)
    }

    // ============ #228 回归 1：incoming 携带炸弹（数千空 reasoning part）被入口滤除 ============

    @Test
    fun `upsertMessages sanitizes incoming empty stream parts bomb`() {
        val msg = Message.Assistant(
            id = "msg-bomb",
            sessionId = "s1",
            parentId = "p",
            time = TimeInfo(created = 1000L, completed = 2000L)
        )
        // 热视图现有：干净的 1 条 text
        handler.setMessages("s1", listOf(MessageWithParts(msg, listOf(
            Part.Text(id = "t0", sessionId = "s1", messageId = "msg-bomb", text = "Hello World")
        ))))

        // incoming：Room 炸弹回灌——1 条 text + 4488 个空 reasoning part（#223 契约 id）
        val bombParts = listOf(
            Part.Text(id = "t0", sessionId = "s1", messageId = "msg-bomb", text = "Hello World")
        ) + (0 until 4488).map { i ->
            Part.Reasoning(id = "msg-bomb_reasoning_ord_$i", sessionId = "s1", messageId = "msg-bomb", text = "")
        }
        val start = System.currentTimeMillis()
        handler.upsertMessages("s1", listOf(MessageWithParts(msg, bombParts)), MergeStrategy.SSE_PRIORITY)
        val elapsed = System.currentTimeMillis() - start

        val result = handler.parts.value["msg-bomb"]!!
        // 炸弹全部滤除：只剩 1 条非空 text
        assertEquals(1, result.size)
        assertEquals("Hello World", (result[0] as Part.Text).text)
        // 守时：4488 part 线性过滤应远低于 1s（回归时 O(N²) 主线程数秒）
        assertTrue("merge took ${elapsed}ms", elapsed < 1000)
    }

    // ============ #228 回归 2：incoming 全空时 existing 侧炸弹也被清扫 ============

    @Test
    fun `upsertMessages all-empty incoming purges existing empty stream parts`() {
        val msg = Message.Assistant(
            id = "msg-purge",
            sessionId = "s1",
            parentId = "p",
            time = TimeInfo(created = 1000L, completed = 2000L)
        )
        // existing：3 个空 reasoning（SSE started 残留形态）
        handler.setMessages("s1", listOf(MessageWithParts(msg, listOf(
            Part.Reasoning(id = "msg-purge_reasoning_ord_0", sessionId = "s1", messageId = "msg-purge", text = ""),
            Part.Reasoning(id = "msg-purge_reasoning_ord_1", sessionId = "s1", messageId = "msg-purge", text = ""),
            Part.Reasoning(id = "msg-purge_reasoning_ord_2", sessionId = "s1", messageId = "msg-purge", text = "")
        ))))

        // incoming：同一消息，parts 全空（Room 快照本身只剩炸弹的形态）
        handler.upsertMessages("s1", listOf(MessageWithParts(msg, listOf(
            Part.Reasoning(id = "msg-purge_reasoning_ord_0", sessionId = "s1", messageId = "msg-purge", text = "")
        ))), MergeStrategy.SSE_PRIORITY)

        val result = handler.parts.value["msg-purge"]!!
        assertTrue(result.all { !((it is Part.Text && it.text.isBlank()) || (it is Part.Reasoning && it.text.isBlank())) })
        assertTrue(result.isEmpty())
    }

    // ============ 测试 2：setMessages 保留 SSE 未完成消息的元数据 ============

    @Test
    fun `setMessages preserves SSE incomplete message metadata`() {
        // SSE：completed=null 的 Assistant 消息（仍在流式输出）
        val sseMsg = Message.Assistant(
            id = "msg-1",
            sessionId = "s1",
            parentId = "parent-1",
            time = TimeInfo(created = 1000L, completed = null)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(sseMsg))

        // REST：同一消息 completed=2000L（服务器知道它已完成）
        val restMsg = Message.Assistant(
            id = "msg-1",
            sessionId = "s1",
            parentId = "parent-1",
            time = TimeInfo(created = 1000L, completed = 2000L)
        )
        handler.setMessages("s1", listOf(MessageWithParts(restMsg, emptyList())))

        // mergeMessageMeta 应将 REST 中的完成时间合并进 SSE 版本
        val merged = handler.messages.value["s1"]!![0] as Message.Assistant
        assertEquals(2000L, merged.time.completed)
    }

    // ============ 测试 3：setMessages 不会清除不在 REST 响应中的消息的 parts ============

    @Test
    fun `setMessages does not clear parts for messages not in REST response`() {
        // SSE：两条消息，各自带有 parts
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

        // REST：只有 msg-1（msg-2 仍在流式输出，尚未进入 REST 快照）
        handler.setMessages("s1", listOf(MessageWithParts(msg1, listOf(part1))))

        // msg-2 应仍在 messages 中
        val messages = handler.messages.value["s1"]!!
        assertEquals(2, messages.size)
        assertEquals("msg-1", messages[0].id)
        assertEquals("msg-2", messages[1].id)

        // msg-2 的 parts 应被保留（current + merged 保留现有键）
        val msg2Parts = handler.parts.value["msg-2"]
        assertNotNull("msg-2 parts should be preserved", msg2Parts)
        assertEquals(1, msg2Parts!!.size)
        assertEquals("Text 2", (msg2Parts[0] as Part.Text).text)
    }

    // ============ 测试 4：handleMessagePartUpdated 保留更长的既有文本而非更短的传入文本 ============

    @Test
    fun `handleMessagePartUpdated keeps longer existing text over shorter incoming text`() {
        // SSE：通过 PartUpdated + PartDelta 累积文本 = "Accumulated SSE text"
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "Accumulated")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "msg-1", partId = "p1",
            field = "text", delta = " SSE text"
        ))
        handler.forceFlushDeltas()

        assertEquals("Accumulated SSE text", (handler.parts.value["msg-1"]!![0] as Part.Text).text)

        // SSE：传入更短文本 "Short" 的 PartUpdated
        val shortPart = Part.Text(id = "p1", sessionId = "s1", messageId = "msg-1", text = "Short")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(shortPart))

        // mergePart 应保留更长的既有文本
        val result = handler.parts.value["msg-1"]!![0] as Part.Text
        assertEquals("Accumulated SSE text", result.text)
    }
}
