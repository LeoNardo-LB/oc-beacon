package dev.leonardo.ocbeacon.ui.screens.chat.input

import dev.leonardo.ocbeacon.domain.model.DshPlanProjection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #310③ PlanChip 显隐纯逻辑（仿 SubagentComposerGate 风格；wire 契约 §③-2）：
 * - DSH only（serverType 门）；
 * - 无投影（OpenCode 恒 null / 首帧前）→ 不出；
 * - 有效目标态（pending ? !active : active）→ 出 chip；
 * - 退出中（active+pending）与稳态关 → 不出（官方 PlanChip 同判据）。
 */
class PlanChipGateTest {

    @Test
    fun `no projection hides chip`() {
        assertFalse(PlanChipGate.chipVisible(true, null))
    }

    @Test
    fun `steady on shows chip on dsh`() {
        assertTrue(PlanChipGate.chipVisible(true, DshPlanProjection(active = true)))
    }

    @Test
    fun `steady off hides chip`() {
        assertFalse(PlanChipGate.chipVisible(true, DshPlanProjection(active = false)))
    }

    @Test
    fun `pending switch on shows chip with progress semantics`() {
        assertTrue(
            PlanChipGate.chipVisible(
                true,
                DshPlanProjection(active = false, pending = true),
            ),
        )
    }

    @Test
    fun `pending leave hides chip`() {
        assertFalse(
            PlanChipGate.chipVisible(
                true,
                DshPlanProjection(active = true, pending = true),
            ),
        )
    }

    @Test
    fun `opencode never shows chip even with projection`() {
        // 防御性：OpenCode 无 plan 域（投影恒 null），若出现非空投影也不出 chip
        assertFalse(
            PlanChipGate.chipVisible(
                false,
                DshPlanProjection(active = true),
            ),
        )
    }
}