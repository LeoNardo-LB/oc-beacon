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

    @Test
    fun `stale tail delta onto terminal same-id part is dropped - #266 live repro`() {
        // 2026-08-30 真机 E2E 定性（诗歌轮）：服务器契约 delta 与 ended 同
        // ordinal（partId 相同），模型尾部自重复被服务器截断——滞留尾 delta 在
        // ended 覆盖后 flush，partId 已注册 → 绕过源头守卫 → applyDelta idx>=0
        // endsWith 不命中（非后缀）→ 盲拼接 → 尾段渲染两遍。终态守卫补齐此洞。
        val handler = MessageEventHandler()
        // 1) 流式累积
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "m1_text_ord_0", "text", "诗歌正文"))
        handler.forceFlushDeltas()
        // 2) 尾部自重复 delta 进批缓冲（未 flush）
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "m1_text_ord_0", "text", "多余尾巴。"))
        // 3) text.ended 权威全量覆盖（同 id，终态）
        handler.handleMessagePartUpdated(
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = "m1_text_ord_0", sessionId = "s1", messageId = "m1",
                    text = "诗歌正文。权威结尾。", time = Part.Text.Time(start = 1, end = 2),
                )
            )
        )
        // 4) 滞留 delta flush：终态 part 不得再被拼接
        handler.forceFlushDeltas()
        val texts = handler.parts.value["m1"]!!.filterIsInstance<Part.Text>()
        assertEquals(1, texts.size)
        assertEquals("诗歌正文。权威结尾。", texts[0].text)
    }

    @Test
    fun `midstream unregistered delta overlapping nonterminal part rebuilds - #266 narrowing`() {
        // 源头守卫收窄：流式中（无终态 part）未注册 delta 与既有 part 内容重叠
        // 可能是合法新 part 的首段——不得源头丢弃（丢弃即内容丢失），交
        // applyDelta 重建兜底；终态包含判定保留。
        val handler = MessageEventHandler()
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "m1_reasoning_ord_0", "reasoning", "第一段思考"))
        handler.forceFlushDeltas()
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "m1_reasoning_ord_1", "reasoning", "段思考"))
        handler.forceFlushDeltas()
        val reasonings = handler.parts.value["m1"]!!.filterIsInstance<Part.Reasoning>()
        assertEquals(2, reasonings.size)
        assertEquals("第一段思考", reasonings[0].text)
        assertEquals("段思考", reasonings[1].text)
    }
}
