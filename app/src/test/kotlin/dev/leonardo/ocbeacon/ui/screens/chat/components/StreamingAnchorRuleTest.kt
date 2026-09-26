package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R1 统一配对谓词决策表（#437 二十五世轮架构审查 A1/A5 根修）。
 *
 * TDD 收敛发现：两轨语义本质同源（锚≤增长源配对），分歧仅是形式（ledger == 多余
 * 收紧）与贴底原点阈值（(0,0)精确 vs <8px）——统一谓词采用帽轨几何 + 8px 阈值，
 * 决策表穷举全状态空间，替代二十轮真机补丁收敛。
 */
class StreamingAnchorRuleTest {

    // ---- 贴底原点（物理跟随，一律免派发）----

    @Test
    fun table_atBottomOrigin_neverPairs() {
        assertEquals(0f, StreamingAnchorRule.pairedDelta(0, 0, 0, 48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(0, 0, 7, 48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(0, 7, 7, 48f))
    }

    // ---- 锚 == 增长源（两轨真机共识）：同帧配对 ----

    @Test
    fun table_anchorEqualsGrowth_pairs() {
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 0, 7, 48f))
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 60, 7, 48f))
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 300, 7, 48f))
        assertEquals(48f, StreamingAnchorRule.pairedDelta(1, 10, 1, 48f))
        // 相等格不受 covered 影响（跟随通道不覆盖「锚正在读增长项」场景）
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 60, 7, 48f, coveredByFollowFamily = true))
    }

    // ---- 分歧格：锚 < 增长源，行为由源类型（coveredByFollowFamily）决定 ----

    @Test
    fun table_anchorBelowGrowth_sourceTypeDecides() {
        // 帽族（流式消息 item，无通道覆盖——z3 横幅区浅滑真机证据）：配对
        assertEquals(48f, StreamingAnchorRule.pairedDelta(0, 21, 7, 48f, coveredByFollowFamily = false))
        assertEquals(48f, StreamingAnchorRule.pairedDelta(3, 80, 7, 48f, coveredByFollowFamily = false))
        // ledger 族（banner/压缩卡，BANNER bottomFollow 已派发补偿——八轮双补震荡证据）：免
        assertEquals(0f, StreamingAnchorRule.pairedDelta(2, 140, 7, 48f, coveredByFollowFamily = true))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(0, 21, 7, 48f, coveredByFollowFamily = true))
    }

    // ---- 读历史：锚在增长源上方 → 免配对 ----

    @Test
    fun table_readingAway_anchorAboveGrowth_noPair() {
        assertEquals(0f, StreamingAnchorRule.pairedDelta(9, 300, 7, 48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(20, 500, 7, 48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 300, 0, 48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(2, 140, 1, 48f))
    }

    // ---- 增长源不可见（回收/间隙）→ 丢弃 ----

    @Test
    fun table_growthNotInLayout_dropped() {
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 300, null, 48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 300, -1, 48f))
    }

    // ---- 收缩/零增量一律不配对 ----

    @Test
    fun table_nonPositiveGrowth_neverPairs() {
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 60, 7, -48f))
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 60, 7, 0f))
    }

    // ---- 决策表穷举：全状态空间一致性（无未定义格子）----

    @Test
    fun table_exhaustive_allCombinations_wellDefined() {
        val anchors = listOf(0 to 0, 0 to 5, 0 to 21, 2 to 140, 7 to 0, 7 to 60, 7 to 300, 9 to 300, 20 to 500)
        val growths = listOf(null, -1, 0, 1, 2, 3, 7, 9, 20)
        val deltas = listOf(48f, -48f, 0f)
        val covered = listOf(false, true)
        var cases = 0
        for ((ai, ao) in anchors) for (g in growths) for (d in deltas) for (c in covered) {
            val r = StreamingAnchorRule.pairedDelta(ai, ao, g, d, c)
            val expect = when {
                d <= 0f -> 0f
                ai == 0 && ao < 8 -> 0f
                g == null || g < 0 -> 0f
                ai == g -> d
                ai < g -> if (c) 0f else d
                else -> 0f
            }
            assertEquals("ai=" + ai + " ao=" + ao + " g=" + g + " d=" + d + " c=" + c, expect, r, 0.001f)
            cases++
        }
        assertEquals(9 * 9 * 3 * 2, cases)
    }
}
