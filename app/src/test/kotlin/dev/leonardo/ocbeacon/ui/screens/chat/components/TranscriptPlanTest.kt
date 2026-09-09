package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.CommandFeedback
import dev.leonardo.ocbeacon.domain.model.CompactionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 TranscriptPlan——seq 锚定归并（displaySeqs 为 newest→oldest 发射序）。
 */
class TranscriptPlanTest {

    private fun cmd(id: String, seq: Long, local: Boolean = false) = TranscriptCardItem.Command(
        CommandFeedback(commandId = id, name = "compact", seq = if (local) 0L else seq, localAccepted = local),
    )

    private fun comp(id: String, seq: Long) = TranscriptCardItem.Compaction(CompactionEntry(compactionId = id, seq = seq))

    private fun plan(
        seqs: List<Long?>,
        cards: List<TranscriptCardItem>,
    ) = TranscriptPlan.build(
        displaySeqs = seqs,
        visualBottomEntryKeys = seqs.indices.map { "b" + it },
        visualTopEntryKeys = seqs.indices.map { "t" + it },
        cards = cards,
    )

    @Test
    fun `card between messages anchors to older message visual top`() {
        // 消息 seq（newest→oldest）：[100, 50]；卡 seq=80 → 锚 = 第一条更旧消息（50，display 1）
        val (extras, trailing) = plan(listOf(100L, 50L), listOf(cmd("c1", 80)))
        assertEquals(listOf("t1"), extras.keys.toList())
        assertEquals(listOf(80L), extras["t1"]!!.before.map { it.sortSeq })
        assertTrue(trailing.isEmpty())
    }

    @Test
    fun `card newer than newest message attaches after display zero`() {
        val (extras, trailing) = plan(listOf(100L, 50L), listOf(cmd("c1", 200)))
        assertEquals(listOf("b0"), extras.keys.toList())
        assertTrue(extras["b0"]!!.before.isEmpty())
        assertEquals(200L, extras["b0"]!!.after.single().sortSeq)
        assertTrue(trailing.isEmpty())
    }

    @Test
    fun `local acceptance placeholder sorts as now`() {
        // seq=0（本地占位）→ MAX：尾部语义（after display 0）
        val (extras, _) = plan(listOf(100L, 50L), listOf(cmd("local-1", 0, local = true)))
        assertEquals("b0", extras.keys.single())
        val card = extras["b0"]!!.after.single() as TranscriptCardItem.Command
        assertEquals(0L, card.feedback.seq)
        assertTrue(card.feedback.localAccepted)
    }

    @Test
    fun `card older than all messages trails at visual top`() {
        val (extras, trailing) = plan(listOf(100L, 50L), listOf(cmd("c1", 10)))
        assertTrue(extras.isEmpty())
        assertEquals(1, trailing.size)
    }

    @Test
    fun `empty transcript degrades to trailing`() {
        val (_, trailing) = plan(emptyList(), listOf(cmd("c1", 10), comp("k1", 20)))
        assertEquals(2, trailing.size)
        assertEquals(10L, trailing[0].sortSeq)
        assertEquals(20L, trailing[1].sortSeq)
    }

    @Test
    fun `non dsh message ids treated as oldest`() {
        // null seq（V1/V2 id）→ MIN：任何卡都「更新于」全部消息 → 视觉尾部挂载
        //（display 0 组 after；现状兼容——V1/V2 本无卡，仅钉死确定性）
        val (extras, trailing) = plan(listOf(null, null), listOf(cmd("c1", 10)))
        assertEquals("b0", extras.keys.single())
        assertEquals(1, extras["b0"]!!.after.size)
        assertEquals(0, trailing.size)
    }

    @Test
    fun `multiple cards same anchor keep seq ascending order`() {
        val (extras, _) = plan(listOf(100L, 50L), listOf(comp("k1", 90), cmd("c1", 80), cmd("c2", 85)))
        val before = extras["t1"]!!.before
        assertEquals(listOf(80L, 85L, 90L), before.map { it.sortSeq })
    }

    @Test
    fun `compaction and command interleave chronologically`() {
        // 压缩族在命令 run 之后（5390 > 5389）：同锚下命令卡在前（视觉更上）
        val (extras, _) = plan(listOf(5394L, 5000L), listOf(cmd("cmd-1", 5389), comp("k1", 5390)))
        val before = extras["t1"]!!.before
        assertEquals(listOf(5389L, 5390L), before.map { it.sortSeq })
        assertEquals(TranscriptCardItem.Command::class, before[0]::class)
        assertEquals(TranscriptCardItem.Compaction::class, before[1]::class)
    }

    @Test
    fun `no cards yields empty plan`() {
        val (extras, trailing) = plan(listOf(100L), emptyList())
        assertTrue(extras.isEmpty() && trailing.isEmpty())
    }

    @Test
    fun `plan keys are stable command or compaction ids`() {
        val (extras, _) = plan(listOf(100L, 50L), listOf(cmd("cmd-9", 80)))
        assertEquals("cmd_feedback_cmd-9", extras["t1"]!!.before.single().planKey)
    }
}
