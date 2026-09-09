package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 表面区间折叠——MessageEventHandler 遮蔽台账消费面：
 * SurfaceRangeReplaced 到达 → 内存移除 seq ∈ [start,end] 消息 + 台账记账；
 * 迟到 MessageUpdated（older page 回放在 surfaceOp 之后）被台账拦截不重加。
 */
class MessageEventHandlerSurface378Test {

    private fun handler() = MessageEventHandler()

    private fun userMsg(id: String, sessionId: String = "s1"): Message.User =
        Message.User(id = id, sessionId = sessionId, time = TimeInfo(created = 1000L))

    private fun pushUser(handler: MessageEventHandler, id: String, sessionId: String = "s1") {
        handler.handle(SseEvent.MessageUpdated(userMsg(id, sessionId)), "srv")
    }

    @Test
    fun `surface replace removes shadowed seq messages from state`() {
        val h = handler()
        pushUser(h, "seq-9")
        pushUser(h, "seq-10")
        pushUser(h, "seq-4999")
        pushUser(h, "seq-5000")
        pushUser(h, "seq-5392") // 替换载体本身（区间外）

        h.handle(
            SseEvent.SurfaceRangeReplaced(
                sessionId = "s1", startSeq = 9, endSeq = 5000, byMessageId = "seq-5392", seq = 5392,
            ),
            "srv",
        )

        val ids = h.messages.value["s1"]!!.map { it.id }
        assertEquals(listOf("seq-5392"), ids)
    }

    @Test
    fun `ledger blocks late re-add of shadowed messages`() {
        val h = handler()
        pushUser(h, "seq-6001") // 区间外幸存消息
        h.handle(SseEvent.SurfaceRangeReplaced("s1", 1, 5000, "seq-6000", seq = 6000), "srv")
        // older page 回放：遮蔽区间内消息迟到
        pushUser(h, "seq-42")
        val ids = h.messages.value["s1"]!!.map { it.id }
        assertFalse("迟到遮蔽消息不得重加", ids.contains("seq-42"))
        assertEquals(listOf("seq-6001"), ids)
    }

    @Test
    fun `non seq ids are never shadowed`() {
        val h = handler()
        pushUser(h, "msg_abc") // V2 形态 id
        h.handle(SseEvent.SurfaceRangeReplaced("s1", 1, 5000, "seq-6000", seq = 6000), "srv")
        assertTrue(h.messages.value["s1"]!!.any { it.id == "msg_abc" })
    }

    @Test
    fun `replay surface replace is idempotent`() {
        val h = handler()
        pushUser(h, "seq-9")
        val event = SseEvent.SurfaceRangeReplaced("s1", 1, 5000, "seq-6000", seq = 6000)
        h.handle(event, "srv")
        h.handle(event, "srv")
        assertTrue(h.messages.value["s1"]!!.isEmpty())
        assertEquals(1, h.shadowedRanges("s1").size)
    }

    @Test
    fun `shadowed ranges flow emits per session`() {
        val h = handler()
        h.handle(SseEvent.SurfaceRangeReplaced("s1", 1, 10, "seq-20", seq = 20), "srv")
        h.handle(SseEvent.SurfaceRangeReplaced("s2", 100, 200, "seq-300", seq = 300), "srv")
        val flow = h.shadowedRangesFlow.value
        assertEquals(1, flow["s1"]!!.size)
        assertEquals(LongRange(100L, 200L), flow["s2"]!!.single())
    }

    @Test
    fun `isShadowed queries ranges inclusively`() {
        val h = handler()
        h.handle(SseEvent.SurfaceRangeReplaced("s1", 9, 5000, "seq-6000", seq = 6000), "srv")
        assertTrue(h.isShadowed("s1", 9))
        assertTrue(h.isShadowed("s1", 5000))
        assertFalse(h.isShadowed("s1", 8))
        assertFalse(h.isShadowed("s1", 5001))
        assertFalse(h.isShadowed("other", 9))
    }
}
