package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #427 P1 切片器外部行为（spec §Testing Decisions 预定 seam）：
 * 边界划分、权重聚合、守恒/幂等、切片阈值判定、内容指纹稳定性。
 * 只断言公开行为，不触及内部结构。
 */
class StepGroupSlicingTest {

    private fun textPart(id: String, text: String = ""): PartGroup.Single =
        PartGroup.Single(
            Part.Text(id = id, sessionId = "s1", messageId = "m1", text = text)
        )

    /** 零长 text part 权重恒 200（200+0），可精确构造阈值边界。 */
    @Test
    fun emptyGroupsProduceNoSlices() {
        assertTrue(sliceStepGroupBodies(emptyList()).isEmpty())
    }

    @Test
    fun lightGroupStaysSingleSlice() {
        val groups = listOf(textPart("p1", "hi"), textPart("p2", "yo"))
        val slices = sliceStepGroupBodies(groups)
        assertEquals(1, slices.size)
        assertEquals(groups, slices[0])
    }

    @Test
    fun heavyGroupSplitsByWeightBudget() {
        // 3 个 3200 权重 part：均超单片预算 2200 → 各自成片
        val groups = (0..2).map { textPart("p" + it, "x".repeat(3000)) }
        val slices = sliceStepGroupBodies(groups)
        assertEquals(3, slices.size)
        assertEquals(groups, slices.flatten())
    }

    @Test
    fun budgetBoundaryStartsNewSlice() {
        // 恰好 2200 权重的 part：第一片装满后，第二 part 必开新片
        val groups = listOf(
            textPart("a", "x".repeat(2000)),
            textPart("b", "x".repeat(2000)),
        )
        val slices = sliceStepGroupBodies(groups)
        assertEquals(2, slices.size)
        assertEquals(listOf(groups[0]), slices[0])
        assertEquals(listOf(groups[1]), slices[1])
    }

    @Test
    fun smallPartsAggregateIntoOneSliceUntilBudget() {
        // 4 × 600 权重 = 2400 > 2200：前三片聚合 1800（未达预算），第四个加入后
        // acc=2400；再来一个必开新片——贪心按序聚合，不回填
        val groups = (0..4).map { textPart("p" + it, "x".repeat(400)) }
        val slices = sliceStepGroupBodies(groups)
        assertEquals(2, slices.size)
        assertEquals(4, slices[0].size)
        assertEquals(1, slices[1].size)
    }

    @Test
    fun contextGroupsSliceAtGroupBoundariesOnly() {
        // Context 组是不可分单元（权重平坦 700）：混组守恒 + 无空片
        val ctx = PartGroup.Context(emptyList())
        val groups = listOf(
            ctx,
            textPart("p1", "x".repeat(2600)),
            ctx,
            textPart("p2", "y".repeat(2600)),
        )
        val slices = sliceStepGroupBodies(groups)
        assertEquals(groups, slices.flatten())
        slices.forEach { assertTrue(it.isNotEmpty()) }
        // 切口只落在组边界：每片的首组 identity 保持输入顺序
        val heads = slices.map { it.first() }
        assertEquals(groups.filter { g -> heads.contains(g) }, heads)
    }

    @Test
    fun indivisibleMonsterPartStaysWhole() {
        // 单体怪物（spec §Implementation：接受片内单体，不再细分）
        val groups = listOf(textPart("monster", "x".repeat(40000)))
        val slices = sliceStepGroupBodies(groups)
        assertEquals(1, slices.size)
        assertEquals(groups, slices[0])
    }

    @Test
    fun reslicingFlattenedOutputIsIdempotent() {
        val groups = (0..9).map { textPart("p" + it, "x".repeat(800)) }
        val once = sliceStepGroupBodies(groups)
        assertEquals(once, sliceStepGroupBodies(once.flatten()))
    }

    @Test
    fun slicingIsDeterministicAcrossCalls() {
        val groups = (0..7).map { textPart("p" + it, "z".repeat(700)) }
        assertEquals(sliceStepGroupBodies(groups), sliceStepGroupBodies(groups))
    }

    // ===== 切片阈值（进入切片/窗口路径 vs 现行整体路径） =====

    @Test
    fun belowThresholdGroupDoesNotNeedSlicing() {
        // 21 × 200 = 4200 < 4400（≈2 屏）→ 现行整体组合路径（spec 故事 8 零回归）
        val light = (0..20).map { textPart("p" + it) }
        assertEquals(4200, stepGroupWeight(light))
        assertFalse(stepGroupNeedsSlicing(light))
    }

    @Test
    fun atThresholdGroupNeedsSlicing() {
        // 22 × 200 = 4400 恰达阈值 → 切片路径
        val heavy = (0..21).map { textPart("p" + it) }
        assertEquals(4400, stepGroupWeight(heavy))
        assertTrue(stepGroupNeedsSlicing(heavy))
    }

    @Test
    fun emptyGroupNeverNeedsSlicing() {
        assertFalse(stepGroupNeedsSlicing(emptyList()))
    }

    // ===== 内容指纹（P2 账本键的原料；同内容稳定，异内容互斥） =====

    @Test
    fun fingerprintStableForSameContent() {
        val a = listOf(textPart("p1", "abc"), textPart("p2", "de"))
        val b = listOf(textPart("p1", "abc"), textPart("p2", "de"))
        assertEquals(sliceFingerprint(a), sliceFingerprint(b))
    }

    @Test
    fun fingerprintDiffersWhenPartIdsDiffer() {
        val a = listOf(textPart("p1", "abc"))
        val b = listOf(textPart("pX", "abc"))
        assertNotEquals(sliceFingerprint(a), sliceFingerprint(b))
    }

    @Test
    fun fingerprintDiffersWhenContentLengthChanges() {
        // 内容增长（工具输出补全/文本变化）不得命中旧账本
        val a = listOf(textPart("p1", "abc"))
        val b = listOf(textPart("p1", "abcdef"))
        assertNotEquals(sliceFingerprint(a), sliceFingerprint(b))
    }

    @Test
    fun fingerprintDiffersWhenGroupCountChanges() {
        val a = listOf(textPart("p1", "abc"), textPart("p2", ""))
        val b = listOf(textPart("p1", "abc"))
        assertNotEquals(sliceFingerprint(a), sliceFingerprint(b))
    }

    @Test
    fun fingerprintOfSameContentDifferentOrderDiffers() {
        val a = listOf(textPart("p1", "abc"), textPart("p2", "de"))
        val b = listOf(textPart("p2", "de"), textPart("p1", "abc"))
        assertNotEquals(sliceFingerprint(a), sliceFingerprint(b))
    }

    @Test
    fun fingerprintDiffersWhenToolOutputGrows() {
        // 双轴审查 #427：工具输出主体在 state——同 id 输出增长不得命中旧账本高度
        fun toolPart(id: String, output: String) = PartGroup.Single(
            Part.Tool(
                id = id, sessionId = "s1", messageId = "m1", tool = "read",
                state = dev.leonardo.ocbeacon.domain.model.ToolState.Completed(output = output),
            )
        )
        val small = listOf(toolPart("w1", "x".repeat(100)))
        val grown = listOf(toolPart("w1", "x".repeat(5000)))
        assertEquals(sliceFingerprint(small), sliceFingerprint(small))
        assertNotEquals(sliceFingerprint(small), sliceFingerprint(grown))
    }

    // ============ #431:巨型 text part 的 markdown 块边界分段 ============

    private fun rawText(id: String, s: String) =
        Part.Text(id = id, sessionId = "s", messageId = "m", text = s)

    @Test
    fun markdownBlocksBlankLinesSeparate() {
        val src = "第一段第一行\n第一段第二行\n\n第二段\n\n第三段"
        assertEquals(
            listOf("第一段第一行\n第一段第二行", "第二段", "第三段"),
            markdownBlocks(src),
        )
    }

    @Test
    fun markdownBlocksTableAtomic() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |\n\n后记"
        val blocks = markdownBlocks(src)
        assertEquals(2, blocks.size)
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |", blocks[0])
    }

    @Test
    fun markdownBlocksFencedCodeAtomic() {
        val src = "说明\n\n```bash\na=1\n\nb=2\n```\n\n结尾"
        val blocks = markdownBlocks(src)
        assertEquals(3, blocks.size)
        assertEquals("```bash\na=1\n\nb=2\n```", blocks[1])
    }

    @Test
    fun splitSmallPartReturnsItself() {
        val p = rawText("t1", "短内容")
        val out = splitHeavyTextPart(p, budgetChars = 100)
        assertEquals(listOf<Part.Text>(p), out) // 原实例零派生
    }

    @Test
    fun splitBigPartWithinBudgetAndDerived() {
        // budget 1100:A(500) 独段;B+C(500+2+500=1002≤1100) 合段
        val p = rawText("t1", "A".repeat(500) + "\n\n" + "B".repeat(500) + "\n\n" + "C".repeat(500))
        val out = splitHeavyTextPart(p, budgetChars = 1100)
        assertEquals(2, out.size)
        assertTrue(out.all { it.text.length <= 1100 })
        assertTrue(out.all { it.synthetic == true })
        assertEquals("t1#sg0", out[0].id)
        assertEquals("t1#sg1", out[1].id)
        // 内容守恒(贪心:A+B=1002 先装满, C 独段)
        assertEquals("A".repeat(500) + "\n\n" + "B".repeat(500), out[0].text)
        assertEquals("C".repeat(500), out[1].text)
    }

    @Test
    fun oversizedAtomicTableStaysWhole() {
        val row = "| " + "x".repeat(40) + " | " + "y".repeat(40) + " |\n"
        val table = ("| h1 | h2 |\n|---|---|\n" + row.repeat(30)).trimEnd()
        val out = splitHeavyTextPart(rawText("t1", table), budgetChars = 1000)
        assertEquals(1, out.size)
        assertEquals(table, out[0].text)
    }

    @Test
    fun giantBlockyTextDistributesAcrossSlices() {
        val giant = ("块" + "x".repeat(200) + "\n\n").repeat(20).trimEnd()
        val slices = sliceStepGroupBodies(listOf(PartGroup.Single(rawText("t1", giant))))
        assertTrue("切片数 " + slices.size + " 应 >=2", slices.size >= 2)
        val flat = slices.flatten()
        assertTrue(
            flat.all {
                ((it as PartGroup.Single).part as Part.Text).text.length <= 2200 * 2
            },
        )
        assertEquals("t1#sg0", (flat[0] as PartGroup.Single).part.id)
    }
}
