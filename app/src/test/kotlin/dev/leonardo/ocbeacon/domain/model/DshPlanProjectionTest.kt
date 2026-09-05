package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #310③ 有效目标态裁剪纯函数（官方 dsh-client-ui-plan PlanChip 同式：
 * `plan.pending ? !plan.active : plan.active`——折叠宿主值，非客户端乐观）。
 * 语义：pending = 存在未生效的目标选择（wanted ≠ active）→ 目标态取反。
 */
class DshPlanProjectionTest {

    @Test
    fun `steady on state is effective`() {
        assertTrue(DshPlanProjection(active = true, pending = false).effective)
    }

    @Test
    fun `steady off state is not effective`() {
        assertFalse(DshPlanProjection(active = false, pending = false).effective)
    }

    @Test
    fun `pending switch on is effective while still inactive`() {
        // 用户已选开启、尚未落盘（下一个 accepted pre-step 生效）→ 目标态=开
        assertTrue(DshPlanProjection(active = false, pending = true).effective)
    }

    @Test
    fun `pending leave hides the target while still active`() {
        // 用户已选退出、尚未落盘 → 目标态=关（chip 退场，不闪烁成关闭态）
        assertFalse(DshPlanProjection(active = true, pending = true).effective)
    }
}