package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R4-B3 步2：displayItems 差量更新（[diffDisplayItemsInto]）——
 * 生产侧承载为 SnapshotStateList（get(i) 为 index 级依赖，set(i) 只失效读者
 * 该槽），流式期间列表长度不变（消息数恒定、仅尾内容变）→ 无 structural
 * 全局失效，重组收敛到流式 item 本体。本测试锁 diff 语义（普通 MutableList）。
 */
class DisplayItemsDiffTest {

    private fun item(id: String, text: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "s", time = TimeInfo(1, 1)),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s", messageId = id, text = text)),
    )

    @Test
    fun `内容未变的槽不写——零失效`() {
        val target = mutableListOf(0 to item("a", "x"), 1 to item("b", "y"))
        val writes = diffDisplayItemsInto(target, listOf(0 to item("a", "x"), 1 to item("b", "y")))
        assertEquals(0, writes)
    }

    @Test
    fun `只有变化槽被写——流式槽单独失效`() {
        val target = mutableListOf(0 to item("a", "x"), 1 to item("b", "y1"))
        val writes = diffDisplayItemsInto(target, listOf(0 to item("a", "x"), 1 to item("b", "y2")))
        assertEquals(1, writes)
        assertEquals("y2", (target[1].second.parts[0] as Part.Text).text)
    }

    @Test
    fun `rawIndex 变化也算槽变化`() {
        val target = mutableListOf(0 to item("a", "x"), 1 to item("b", "y"))
        val writes = diffDisplayItemsInto(target, listOf(0 to item("a", "x"), 2 to item("b", "y")))
        assertEquals(1, writes)
    }

    @Test
    fun `长度变化走全量重置`() {
        val target = mutableListOf(0 to item("a", "x"))
        val writes = diffDisplayItemsInto(target, listOf(0 to item("a", "x"), 1 to item("b", "y")))
        assertEquals(2, writes)
        assertEquals(2, target.size)
    }
}
