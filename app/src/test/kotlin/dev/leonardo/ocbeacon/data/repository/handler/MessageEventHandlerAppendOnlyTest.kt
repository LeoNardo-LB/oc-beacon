package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #234 战役二封洞验证：appendOnly 直通路径的零信息 part 不变量。
 *
 * 封洞前 upsertAppendOnly 对新 messageId 的 parts 直通 _parts（不过
 * mergePartsList）——不变量仅靠上游 REST mapper 过滤兜底；封洞后经
 * MessageMergeEngine.sanitized，结构性成立。
 */
class MessageEventHandlerAppendOnlyTest {

    @Test
    fun `append-only does not register zero-info parts for new messages`() {
        val handler = MessageEventHandler()
        val msg = Message.Assistant(id = "a1", sessionId = "s1", time = TimeInfo(created = 1L), parentId = "")
        val parts = listOf(
            Part.Reasoning(id = "a1_reasoning_ord_0", sessionId = "s1", messageId = "a1", text = ""),
            Part.Text(id = "a1_text_ord_0", sessionId = "s1", messageId = "a1", text = "正文"),
        )
        handler.upsertMessages("s1", listOf(MessageWithParts(msg, parts)), MergeStrategy.APPEND_ONLY)
        val registered = handler.parts.value["a1"].orEmpty()
        assertEquals(listOf("a1_text_ord_0"), registered.map { it.id })
    }

    @Test
    fun `stale buffered delta after terminal replace does not duplicate tail - #265`() {
        val handler = MessageEventHandler()
        // 1) 流式 delta → 派生 id part 建立
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "m1_text_ord_0", "text", "正文开头"))
        handler.forceFlushDeltas()
        assertEquals("正文开头", (handler.parts.value["m1"]!!.single() as Part.Text).text)
        // 2) 完结权威替换：text.ended 全量值 + partId 换代（isTerminal 覆盖）
        handler.handleMessagePartUpdated(
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = "prt_srv_1", sessionId = "s1", messageId = "m1",
                    text = "正文开头。结尾句。", time = Part.Text.Time(start = 1, end = 2),
                )
            )
        )
        // 3) 滞留尾 delta flush：内容已并入权威文本 → 源头守卫丢弃
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "m1_text_ord_0", "text", "结尾句。"))
        handler.forceFlushDeltas()
        val texts = handler.parts.value["m1"]!!.filterIsInstance<Part.Text>()
        assertEquals(1, texts.size)
        val t = texts[0].text
        assertEquals(1, t.split("结尾句。").size - 1)
        assertTrue(t.endsWith("结尾句。"))
    }
}
