package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
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
}
