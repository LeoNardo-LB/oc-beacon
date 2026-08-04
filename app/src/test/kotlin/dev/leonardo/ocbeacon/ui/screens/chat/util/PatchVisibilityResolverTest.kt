package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [suppressRepeatedPatchHashes] 的测试。
 *
 * 覆盖矩阵：
 * - 基本去重：跨消息相同 hash、hash 变化、非 patch 重置、空 hash
 * - 边界：空列表、单条消息、仅用户、消息内去重
 * - 极端/边界：空白修剪、混合序列、大 hash、空白/非空白交替
 */
class PatchVisibilityResolverTest {

    @Test
    fun `hides repeated non-blank hash across assistant messages`() {
        val firstPatch = patchPart(msgId = "a-1", partId = "p-1", hash = "same-hash")
        val secondPatch = patchPart(msgId = "a-2", partId = "p-2", hash = "same-hash")

        val result = suppressRepeatedPatchHashes(
            listOf(assistantMsg("a-1", firstPatch), assistantMsg("a-2", secondPatch))
        )

        assertEquals(listOf(firstPatch), result[0].parts.filterIsInstance<Part.Patch>())
        assertTrue("second patch should be suppressed", result[1].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun `keeps patch when hash changes`() {
        val firstPatch = patchPart(msgId = "a-1", partId = "p-1", hash = "hash-1")
        val secondPatch = patchPart(msgId = "a-2", partId = "p-2", hash = "hash-2")

        val result = suppressRepeatedPatchHashes(
            listOf(assistantMsg("a-1", firstPatch), assistantMsg("a-2", secondPatch))
        )

        assertEquals(listOf(firstPatch), result[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(secondPatch), result[1].parts.filterIsInstance<Part.Patch>())
    }

    @Test
    fun `does not reset dedup state for non-patch assistant parts`() {
        val firstPatch = patchPart(msgId = "a-1", partId = "p-1", hash = "same-hash")
        val textPart = textPart(msgId = "a-2", partId = "t-1", text = "daily report")
        val repeatedPatch = patchPart(msgId = "a-3", partId = "p-2", hash = "same-hash")

        val result = suppressRepeatedPatchHashes(
            listOf(
                assistantMsg("a-1", firstPatch),
                assistantMsg("a-2", textPart),
                assistantMsg("a-3", repeatedPatch)
            )
        )

        assertEquals(listOf(firstPatch), result[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(textPart), result[1].parts.filterIsInstance<Part.Text>())
        assertTrue("repeated patch after text should still be suppressed", result[2].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun `keeps blank hash patch visible`() {
        val firstPatch = patchPart(msgId = "a-1", partId = "p-1", hash = "")
        val secondPatch = patchPart(msgId = "a-2", partId = "p-2", hash = "")

        val result = suppressRepeatedPatchHashes(
            listOf(assistantMsg("a-1", firstPatch), assistantMsg("a-2", secondPatch))
        )

        assertEquals(listOf(firstPatch), result[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(secondPatch), result[1].parts.filterIsInstance<Part.Patch>())
    }

    // ── 边界情况 ──────────────────────────────────────────────

    @Test
    fun `empty messages list returns empty`() {
        val result = suppressRepeatedPatchHashes(emptyList())
        assertEquals(emptyList<ChatMessage>(), result)
    }

    @Test
    fun `single assistant message with patch unchanged`() {
        val patch = patchPart(msgId = "a-1", partId = "p-1", hash = "unique-hash")
        val msg = assistantMsg("a-1", patch)

        val result = suppressRepeatedPatchHashes(listOf(msg))

        assertEquals(listOf(patch), result[0].parts.filterIsInstance<Part.Patch>())
    }

    @Test
    fun `user only messages pass through unchanged`() {
        val msgs = listOf(
            ChatMessage(Message.User(id = "u-1", sessionId = "s", time = TimeInfo(1L)), emptyList()),
            ChatMessage(Message.User(id = "u-2", sessionId = "s", time = TimeInfo(2L)), emptyList())
        )

        val result = suppressRepeatedPatchHashes(msgs)

        assertEquals(msgs, result)
    }

    @Test
    fun `multiple patches same hash in single message are deduplicated`() {
        val patch1 = patchPart(msgId = "a-1", partId = "p-1", hash = "dup")
        val patch2 = patchPart(msgId = "a-1", partId = "p-2", hash = "dup")

        val result = suppressRepeatedPatchHashes(listOf(assistantMsg("a-1", patch1, patch2)))

        val visiblePatches = result[0].parts.filterIsInstance<Part.Patch>()
        assertEquals("first patch kept, second deduped within same message", 1, visiblePatches.size)
        assertEquals(patch1, visiblePatches[0])
    }

    @Test
    fun `hash with surrounding whitespace is trimmed for comparison`() {
        val firstPatch = patchPart(msgId = "a-1", partId = "p-1", hash = "  abc  ")
        val secondPatch = patchPart(msgId = "a-2", partId = "p-2", hash = "abc")

        val result = suppressRepeatedPatchHashes(
            listOf(assistantMsg("a-1", firstPatch), assistantMsg("a-2", secondPatch))
        )

        assertEquals(listOf(firstPatch), result[0].parts.filterIsInstance<Part.Patch>())
        assertTrue("whitespace-padded hash should match bare hash", result[1].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    // ── 极端/边界情况 ────────────────────────────────────────

    @Test
    fun `mixed user assistant sequence dedups only in assistant messages`() {
        val patch1 = patchPart(msgId = "a-1", partId = "p-1", hash = "shared")
        val patch2 = patchPart(msgId = "a-2", partId = "p-2", hash = "shared")

        val result = suppressRepeatedPatchHashes(
            listOf(
                ChatMessage(Message.User(id = "u-1", sessionId = "s", time = TimeInfo(1L)), emptyList()),
                assistantMsg("a-1", patch1),
                ChatMessage(Message.User(id = "u-2", sessionId = "s", time = TimeInfo(2L)), emptyList()),
                assistantMsg("a-2", patch2)
            )
        )

        // 用户消息保持不变
        assertEquals(2, result.filter { it.message is Message.User }.size)
        // 第一个 assistant patch 保留，第二个被去重
        assertEquals(listOf(patch1), result[1].parts.filterIsInstance<Part.Patch>())
        assertTrue(result[3].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun `large hash value handled correctly`() {
        val largeHash = "h".repeat(1024)          // 1KB 的 hash
        val firstPatch = patchPart(msgId = "a-1", partId = "p-1", hash = largeHash)
        val secondPatch = patchPart(msgId = "a-2", partId = "p-2", hash = largeHash)

        val result = suppressRepeatedPatchHashes(
            listOf(assistantMsg("a-1", firstPatch), assistantMsg("a-2", secondPatch))
        )

        assertEquals(listOf(firstPatch), result[0].parts.filterIsInstance<Part.Patch>())
        assertTrue("large hash should still dedup correctly", result[1].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun `blank and non-blank hash alternating keeps blank patches visible`() {
        // blank → non-blank(X) → blank → non-blank(X)：blank 始终可见，X 被去重
        val blank1 = patchPart(msgId = "a-1", partId = "p-1", hash = "")
        val x1 = patchPart(msgId = "a-2", partId = "p-2", hash = "X")
        val blank2 = patchPart(msgId = "a-3", partId = "p-3", hash = "")
        val x2 = patchPart(msgId = "a-4", partId = "p-4", hash = "X")

        val result = suppressRepeatedPatchHashes(
            listOf(
                assistantMsg("a-1", blank1),
                assistantMsg("a-2", x1),
                assistantMsg("a-3", blank2),
                assistantMsg("a-4", x2)
            )
        )

        // 空白 patch 始终可见
        assertEquals(listOf(blank1), result[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(blank2), result[2].parts.filterIsInstance<Part.Patch>())
        // X1 保留，X2 被去重（中间的空白不会重置）
        assertEquals(listOf(x1), result[1].parts.filterIsInstance<Part.Patch>())
        assertTrue(result[3].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    // ── 辅助函数 ─────────────────────────────────────────────────────

    private fun assistantMsg(id: String, vararg parts: Part): ChatMessage = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = 1000L, completed = 2000L),
            parentId = "",
            modelId = "test-model"
        ),
        parts = parts.toList()
    )

    private fun patchPart(msgId: String, partId: String, hash: String): Part.Patch = Part.Patch(
        id = partId,
        sessionId = "test-session",
        messageId = msgId,
        hash = hash,
        files = listOf("README.md")
    )

    private fun textPart(msgId: String, partId: String, text: String): Part.Text = Part.Text(
        id = partId,
        sessionId = "test-session",
        messageId = msgId,
        text = text
    )
}
