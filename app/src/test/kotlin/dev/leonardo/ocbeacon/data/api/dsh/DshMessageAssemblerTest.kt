package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshMessageAssembler 测试（backlog #276 步骤③ MessageApi 历史签名适配）。
 *
 * MessageApi.listMessages 返回 MessagePage（强类型 List<MessageWithParts>）——
 * DSH 历史是原始事件流，经 DshHistoryFolder/DshEventMapper 产出 SseEvent 序列后，
 * 由本装配器折成 MessageWithParts（MessageUpdated 建壳 + MessagePartUpdated 挂 part，
 * 保序）。
 */
class DshMessageAssemblerTest {

    @Test
    fun `assembles messages with parts in event order`() {
        val events = listOf(
            SseEvent.MessageUpdated(Message.User(id = "seq-5", sessionId = "s", time = TimeInfo(created = 5L))),
            SseEvent.MessagePartUpdated(
                Part.Text(id = "p1", sessionId = "s", messageId = "seq-5", text = "hi",
                    time = Part.Text.Time(start = 5L, end = 5L)),
            ),
            SseEvent.MessageUpdated(
                Message.Assistant(id = "seq-9", sessionId = "s", time = TimeInfo(created = 9L, completed = 9L), parentId = ""),
            ),
            SseEvent.MessagePartUpdated(
                Part.Reasoning(id = "p2", sessionId = "s", messageId = "seq-9", text = "think",
                    time = Part.Reasoning.Time(start = 9L, end = 9L)),
            ),
            SseEvent.MessagePartUpdated(
                Part.Text(id = "p3", sessionId = "s", messageId = "seq-9", text = "answer",
                    time = Part.Text.Time(start = 9L, end = 9L)),
            ),
        )
        val page = DshMessageAssembler.assemble(events)
        assertEquals(2, page.size)
        assertEquals("seq-5", page[0].info.id)
        assertEquals(1, page[0].parts.size)
        assertEquals("seq-9", page[1].info.id)
        assertEquals(listOf("p2", "p3"), page[1].parts.map { it.id })
    }

    @Test
    fun `message removal and duplicate updates are handled idempotently`() {
        val user = Message.User(id = "seq-1", sessionId = "s", time = TimeInfo(created = 1L))
        val events = listOf(
            SseEvent.MessageUpdated(user),
            // 整装到达前拆除流式宿主（#275 桥）：宿主从未建壳 → 无害
            SseEvent.MessageRemoved(sessionId = "s", messageId = "dsh-t1s1"),
            SseEvent.MessageUpdated(user), // 重复 upsert（重放）→ 单壳
        )
        val page = DshMessageAssembler.assemble(events)
        assertEquals(1, page.size)
        assertEquals(0, page[0].parts.size)
    }

    @Test
    fun `removal of a seen message drops it from output`() {
        val user = Message.User(id = "seq-2", sessionId = "s", time = TimeInfo(created = 2L))
        val events = listOf(
            SseEvent.MessageUpdated(user),
            SseEvent.MessageRemoved(sessionId = "s", messageId = "seq-2"),
        )
        assertTrue(DshMessageAssembler.assemble(events).isEmpty())
    }

    @Test
    fun `part before message shell is attached on late update`() {
        // part 先于壳（防御序）：装配器暂存未知 messageId 的 part，壳到达时并入
        // ——DSH 映射器恒壳先 part，此处防御未来映射变体。
        val events = listOf(
            SseEvent.MessagePartUpdated(
                Part.Text(id = "p9", sessionId = "s", messageId = "seq-7", text = "x",
                    time = Part.Text.Time(start = 7L, end = 7L)),
            ),
            SseEvent.MessageUpdated(
                Message.Assistant(id = "seq-7", sessionId = "s", time = TimeInfo(created = 7L), parentId = ""),
            ),
        )
        val page = DshMessageAssembler.assemble(events)
        assertEquals(1, page.size)
        assertEquals(1, page[0].parts.size)
    }

    @Test
    fun `tool host messages assemble with tool parts`() {
        val events = listOf(
            SseEvent.MessageUpdated(
                Message.Assistant(id = "dsh-call-c1", sessionId = "s", time = TimeInfo(created = 3L), parentId = ""),
            ),
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = "c1", sessionId = "s", messageId = "dsh-call-c1", callId = "c1",
                    tool = "bash",
                    state = dev.leonardo.ocbeacon.domain.model.ToolState.Pending(input = emptyMap()),
                ),
            ),
        )
        val page = DshMessageAssembler.assemble(events)
        assertEquals(1, page.size)
        assertTrue(page[0].parts.first() is Part.Tool)
    }
}
