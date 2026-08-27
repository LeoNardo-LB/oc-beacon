package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #246 定音（2026-08-27 真机截图+ScrollDiag 算术链）：
 * displayItems 为最新在前（reverseLayout 索引 0 在屏幕底部）。分片条目若按
 * 文档正序（c0→c1）发射，head 会被当成「更新」排到屏幕更下方——用户从底部
 * 上滚先碰到尾片（「直接从 5. 索引下推 开始」），头片（1–4 节）被埋在会话
 * 更低处。契约：chunk 条目必须逆文档序发射（尾片先入列），且
 * displayEntryStart 仍钉在头片 c0（跳转落点=turn 首 chunk 语义不变）。
 */
class ChunkEntryOrderTest {

    private suspend fun parse(text: String): com.mikepenz.markdown.model.State.Success {
        val normalized = dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender(text, isUser = false)
        val flow = com.mikepenz.markdown.model.parseMarkdownFlow(normalized)
        return withTimeout(10_000) {
            flow.first { it is com.mikepenz.markdown.model.State.Success }
        } as com.mikepenz.markdown.model.State.Success
    }

    private fun assistant(id: String, partId: String, text: String) = ChatMessage(
        message = Message.Assistant(id = id, sessionId = "s1", time = TimeInfo(2), parentId = "p0"),
        parts = listOf(Part.Text(id = partId, sessionId = "s1", messageId = id, text = text)),
    )

    private fun user(id: String, text: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "s1", time = TimeInfo(1)),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = text)),
    )

    @Test
    fun assistantChunksEmitTailFirstForNewestFirstList() = runBlocking(Dispatchers.Default) {
        val doc = buildString {
            append("# 总览\n\n")
            repeat(8) { i ->
                append("## 章节" + (i + 1) + "\n\n章节" + (i + 1) + "正文段落，内容足够长以触发分片预算。\n\n")
            }
        }
        val plan = computeChunkPlan("p_a", parse(doc), minChars = 100, targetChars = 150)!!
        val a = assistant("m_a", "p_a", doc)
        // 最新在前：assistant 回复（新）在 index 0，用户提问（旧）在 index 1
        val displayItems = listOf(0 to a, 1 to user("m_u", "问题"))
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = null,
            chunkPlans = mapOf("p_a" to plan),
            recentStreamedTurnKeys = emptySet(),
        )
        val count = plan.ranges.size
        val chunkKeys = chat.entries.dropLast(1).map { it.key }
        // 尾片先入列：c<count-1> … c1 … c0（屏幕上 c0 在最上）
        assertEquals((count - 1 downTo 0).map { "t_m_a#c" + it }, chunkKeys)
        // displayEntryStart 仍钉在头片 c0（跳转落点=含标签栏的 turn 首 chunk）
        assertEquals(count - 1, chat.displayEntryStart[0])
        assertEquals(count, chat.displayEntryStart[1])
    }

    @Test
    fun userChunksEmitTailFirstForNewestFirstList() {
        val body = (1..120).joinToString("\n") { "第 " + it + " 行：用户粘贴长文用于分片测试，行内容填充。" }
        val u = user("m_big", body)
        val displayItems = listOf(0 to u)
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = emptyMap(),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
        )
        val first = chat.entries.firstOrNull() as? ChatEntry.UserChunk
            ?: throw AssertionError("长用户消息应产生 UserChunk 分片，实际 entries=" + chat.entries.map { it.key })
        val count = first.chunkCount
        assertEquals((count - 1 downTo 0).map { "u_m_big#c" + it }, chat.entries.map { it.key })
        assertEquals(count - 1, chat.displayEntryStart[0])
    }
}
