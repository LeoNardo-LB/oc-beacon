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
}
