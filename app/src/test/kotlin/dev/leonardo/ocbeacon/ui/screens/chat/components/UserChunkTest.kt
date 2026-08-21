package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.util.computeTurnGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户长消息纯文本分片（2026-08-22 滚动巨帧根治）：
 * - splitUserTextChunks：门槛/预算/行边界/超长单行硬切/重组等价
 * - buildChatEntries：UserChunk 发射条件（保守判定——synthetic/压缩/多 part 不分片）
 * - key/双向索引语义（u_ 前缀保持，displayEntryStart 指向首段）
 */
class UserChunkTest {

    // ============ splitUserTextChunks ============

    @Test
    fun `短于门槛不分片`() {
        assertNull(splitUserTextChunks("p", plainText(USER_CHUNK_MIN_CHARS - 1), USER_CHUNK_MIN_CHARS, USER_CHUNK_TARGET_CHARS))
    }

    @Test
    fun `多行文本按预算切段且重组等价`() {
        // 40 行 × 100 字符 = 4000 字符 → 至少 2 段
        val text = (0 until 40).joinToString("\n") { plainLine(100) }
        val plan = splitUserTextChunks("p", text, USER_CHUNK_MIN_CHARS, USER_CHUNK_TARGET_CHARS)
        assertNotNull(plan)
        val segs = plan!!.segments
        assertTrue("应切出多段, got ${segs.size}", segs.size >= 2)
        // 每段（除末段）达到预算；行边界保留（无行内截断）
        segs.dropLast(1).forEach { s ->
            assertTrue("段长 ${s.length} 应 >= 预算", s.length >= USER_CHUNK_TARGET_CHARS)
            assertTrue("段应整行组成", s.lines().all { it.length <= 100 })
        }
        // 重组等价（尾部多个换行可容忍）
        assertEquals(text + "\n", segs.joinToString(""))
    }

    @Test
    fun `无换行超长单行保守不切分`() {
        // 单行无换行：切不出多段（不插入原文没有的换行——零内容变异原则）
        assertNull(splitUserTextChunks("p", plainText(7000), USER_CHUNK_MIN_CHARS, USER_CHUNK_TARGET_CHARS))
    }

    @Test
    fun `切不出多段返回 null`() {
        // 恰好门槛但预算高于总长 → 单段 → null
        assertNull(splitUserTextChunks("p", plainText(USER_CHUNK_MIN_CHARS), USER_CHUNK_MIN_CHARS, USER_CHUNK_TARGET_CHARS * 4))
    }

    // ============ buildChatEntries 发射 ============

    @Test
    fun `长单文本用户消息发射 UserChunk`() {
        val text = multiLineText(USER_CHUNK_MIN_CHARS + 500)
        val user = userMsgWith(listOf(textPart("t0", text)))
        val entries = build(listOf(user))
        val chunks = entries.entries.filterIsInstance<ChatEntry.UserChunk>()
        assertTrue("应发射 UserChunk, got ${entries.entries}", chunks.isNotEmpty())
        assertEquals("u_m0#c0", chunks.first().key)
        assertEquals("u_m0#c" + (chunks.size - 1), chunks.last().key)
        assertTrue(chunks.first().isFirst)
        assertTrue(chunks.last().isLast)
        // 双向索引：display 0 → 首段 entry 序号 0
        assertEquals(0, entries.displayEntryStart[0])
        chunks.forEach { assertEquals(0, it.displayIndex) }
        // 不再有整 turn 条目
        assertTrue(entries.entries.none { it is ChatEntry.Turn })
    }

    @Test
    fun `短用户消息保持 Turn`() {
        val user = userMsgWith(listOf(textPart("t0", plainText(100))))
        val entries = build(listOf(user))
        assertEquals(1, entries.entries.size)
        assertTrue(entries.entries[0] is ChatEntry.Turn)
        assertEquals("u_m0", entries.entries[0].key)
    }

    @Test
    fun `多文本 part 用户消息不分片`() {
        val parts = listOf(
            textPart("t0", plainText(USER_CHUNK_MIN_CHARS + 100)),
            textPart("t1", plainText(50)),
        )
        val entries = build(listOf(userMsgWith(parts)))
        assertTrue(entries.entries[0] is ChatEntry.Turn)
    }

    @Test
    fun `synthetic 角色用户消息不分片`() {
        val msg = ChatMessage(
            message = Message.User(id = "u0", sessionId = "s", time = TimeInfo(created = 1L), role = "synthetic"),
            parts = listOf(textPart("t0", plainText(USER_CHUNK_MIN_CHARS + 100))),
        )
        val entries = build(listOf(msg))
        assertTrue(entries.entries[0] is ChatEntry.Turn)
    }

    @Test
    fun `压缩触发用户消息不分片`() {
        val msg = ChatMessage(
            message = Message.User(id = "u0", sessionId = "s", time = TimeInfo(created = 1L)),
            parts = listOf(
                textPart("t0", plainText(USER_CHUNK_MIN_CHARS + 100)),
                Part.Compaction(id = "c0", sessionId = "s", messageId = "m", summary = "sum"),
            ),
        )
        val entries = build(listOf(msg))
        assertTrue(entries.entries[0] is ChatEntry.Turn)
    }

    @Test
    fun `长用户消息与 assistant 消息共存时索引正确`() {
        val user = userMsgWith(listOf(textPart("t0", multiLineText(USER_CHUNK_MIN_CHARS + 2000))))
        val assistant = ChatMessage(
            message = Message.Assistant(
                id = "a0", sessionId = "s",
                time = TimeInfo(created = 2L, completed = 3L),
                parentId = "", modelId = "m",
            ),
            parts = listOf(textPart("t1", plainText(200))),
        )
        val entries = build(listOf(user, assistant))
        val chunks = entries.entries.filterIsInstance<ChatEntry.UserChunk>()
        val turns = entries.entries.filterIsInstance<ChatEntry.Turn>()
        assertEquals(1, turns.size) // assistant
        assertTrue(chunks.size >= 2)
        // 索引一致性：entryDisplayIndex 全程指向正确 display
        entries.entries.forEachIndexed { i, e ->
            val expected = if (e is ChatEntry.UserChunk) 0 else 1
            assertEquals(expected, entries.entryDisplayIndex[i])
        }
    }

    // ============ fixtures ============

    private fun build(msgs: List<ChatMessage>): ChatEntries {
        val displayItems = msgs.mapIndexed { idx, m -> idx to m }
        val groups = computeTurnGroups(msgs)
        return buildChatEntries(displayItems, groups, null, emptyMap(), emptySet())
    }

    private fun userMsgWith(parts: List<Part>) = ChatMessage(
        message = Message.User(id = "m0", sessionId = "s", time = TimeInfo(created = 1L)),
        parts = parts,
    )

    private fun textPart(id: String, text: String) =
        Part.Text(id = id, sessionId = "s", messageId = "m-" + id, text = text)

    private fun plainText(chars: Int) = buildString {
        repeat(chars) { append(('a' + (it % 26))) }
    }

    /** 无换行的定长行内容（行内全字母，长度可控）。 */
    private fun plainLine(chars: Int): String = buildString {
        repeat(chars) { append(('A' + (it % 26))) }
    }

    /** 多行文本（每行 100 字符 + 换行）——用户粘贴文档的真实形态。 */
    private fun multiLineText(totalChars: Int): String =
        (0 until (totalChars / 100)).joinToString("\n") { plainLine(100) }
}
