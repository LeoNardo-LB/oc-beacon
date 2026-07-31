package dev.leonardo.octether.ui.screens.chat.util

import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.TimeInfo
import dev.leonardo.octether.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [suppressRepeatedPatchHashes].
 *
 * Coverage matrix:
 * - Basic dedup: cross-message same hash, hash change, non-patch reset, blank hash
 * - Boundary: empty list, single message, user-only, in-message dedup
 * - Edge/extreme: whitespace trimming, mixed sequences, large hash, blank/non-blank alternating
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

    // ── Boundary cases ──────────────────────────────────────────────

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

    // ── Extreme / edge cases ────────────────────────────────────────

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

        // User messages unchanged
        assertEquals(2, result.filter { it.message is Message.User }.size)
        // First assistant patch kept, second deduped
        assertEquals(listOf(patch1), result[1].parts.filterIsInstance<Part.Patch>())
        assertTrue(result[3].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun `large hash value handled correctly`() {
        val largeHash = "h".repeat(1024)  // 1KB hash
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
        // blank → non-blank(X) → blank → non-blank(X): blank always visible, X deduped
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

        // Blank patches always visible
        assertEquals(listOf(blank1), result[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(blank2), result[2].parts.filterIsInstance<Part.Patch>())
        // X1 kept, X2 deduped (blank in between doesn't reset)
        assertEquals(listOf(x1), result[1].parts.filterIsInstance<Part.Patch>())
        assertTrue(result[3].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    // ── Helpers ─────────────────────────────────────────────────────

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
