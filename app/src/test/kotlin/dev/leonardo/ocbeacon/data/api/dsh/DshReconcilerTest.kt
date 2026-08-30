package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DshReconciler 断连对账测试（backlog #275 组件 C；设计文档 §1.6-5 重连无游标 → 对账协议）。
 *
 * 协议：mux 重连后服务端自动重推全部会话 session/subscribed{lastSeq} 基线 → 客户端
 * 比对本地已应用 seq 发现缺口 → session.history{beforeSeq, maxMessages} 向前翻页回填。
 * 本组件是纯状态机（网络编排在 #276）：local = 本地已应用 seq 表，baseline = 订阅基线。
 *
 * 边界契约（任务定稿）：baseline > local + 1 → Backfill；相等（恰落后一个 seq）或
 * 更小 → 无需动作。该 off-by-one 边界的实况语义验证留给 #276 E2E（见实现注释）。
 */
class DshReconcilerTest {

    @Test
    fun `gap produces backfill with baseline cursor and default page size`() {
        val plan = DshReconciler.plan(
            local = mapOf("s1" to 10L),
            baseline = mapOf("s1" to 25L),
        )
        assertEquals(
            listOf(DshReconcileAction.Backfill(sessionId = "s1", beforeSeq = 25L, maxMessages = 50)),
            plan.actions,
        )
    }

    @Test
    fun `no action when baseline equals local plus one or is not ahead`() {
        // baseline == local + 1：恰落后一个 seq——任务契约定为无需回填
        assertEquals(
            DshReconcilePlan(emptyList()),
            DshReconciler.plan(local = mapOf("s1" to 24L), baseline = mapOf("s1" to 25L)),
        )
        // baseline <= local：本地已持平/超前（重放幂等场景）
        assertEquals(
            DshReconcilePlan(emptyList()),
            DshReconciler.plan(local = mapOf("s1" to 25L), baseline = mapOf("s1" to 25L)),
        )
        assertEquals(
            DshReconcilePlan(emptyList()),
            DshReconciler.plan(local = mapOf("s1" to 30L), baseline = mapOf("s1" to 25L)),
        )
    }

    @Test
    fun `session vanished when local has session absent from baseline`() {
        val plan = DshReconciler.plan(
            local = mapOf("s1" to 10L, "gone" to 5L),
            baseline = mapOf("s1" to 10L),
        )
        assertEquals(listOf(DshReconcileAction.SessionVanished("gone")), plan.actions)
    }

    @Test
    fun `new baseline session produces initial fetch with its cursor`() {
        val plan = DshReconciler.plan(
            local = emptyMap(),
            baseline = mapOf("fresh" to 42L),
        )
        assertEquals(
            listOf(DshReconcileAction.InitialFetch(sessionId = "fresh", beforeSeq = 42L, maxMessages = 50)),
            plan.actions,
        )
    }

    @Test
    fun `mixed inputs produce all action kinds sorted deterministically with custom page size`() {
        val plan = DshReconciler.plan(
            local = mapOf("b-gap" to 1L, "d-vanished" to 1L, "c-synced" to 9L),
            baseline = mapOf("b-gap" to 100L, "c-synced" to 10L, "a-new" to 7L),
            pageSize = 25,
        )
        assertEquals(
            listOf(
                DshReconcileAction.InitialFetch("a-new", beforeSeq = 7L, maxMessages = 25),
                DshReconcileAction.Backfill("b-gap", beforeSeq = 100L, maxMessages = 25),
                // c-synced：baseline 10 == local 9 + 1 → 无需
                DshReconcileAction.SessionVanished("d-vanished"),
            ),
            plan.actions,
        )
    }

    @Test
    fun `empty inputs produce empty plan and page size must be positive`() {
        assertEquals(DshReconcilePlan(emptyList()), DshReconciler.plan(emptyMap(), emptyMap()))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DshReconciler.plan(emptyMap(), emptyMap(), pageSize = 0)
        }
    }
}
