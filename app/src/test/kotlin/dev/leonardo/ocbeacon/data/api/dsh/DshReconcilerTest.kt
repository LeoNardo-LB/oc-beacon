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
 * 边界契约（#276 终验 E2E 定音，2026-08-31）：lastSeq 是**排他水位**——服务器
 * subscribed 帧 lastSeq = session.seq - 1（seq == lastSeq 的事件已持久化但不会从流交付），
 * 且 history 分页为 seq < beforeSeq 严格排他 → 回放游标必须传 baseline + 1 才能覆盖
 * 最新一条事件；缺口判据必须 baseline > local（恰落后一条也要回填）。旧契约
 * （baseline > local + 1 / beforeSeq = baseline）把订阅时点的最新事件（常为 turn/end）
 * 永久排除在流与回放两路之外 → FSM 停 Busy → 列表「处理中」徽章滞留。
 */
class DshReconcilerTest {

    @Test
    fun `gap produces backfill with exclusive cursor and default page size`() {
        val plan = DshReconciler.plan(
            local = mapOf("s1" to 10L),
            baseline = mapOf("s1" to 25L),
        )
        assertEquals(
            listOf(DshReconcileAction.Backfill(sessionId = "s1", beforeSeq = 26L, maxMessages = 50)),
            plan.actions,
        )
    }

    @Test
    fun `backfill when baseline equals local plus one, no action when not ahead`() {
        // baseline == local + 1：恰落后一个 seq——seq == lastSeq 的事件不从流交付，必须回填
        assertEquals(
            listOf(DshReconcileAction.Backfill(sessionId = "s1", beforeSeq = 26L, maxMessages = 50)),
            DshReconciler.plan(local = mapOf("s1" to 24L), baseline = mapOf("s1" to 25L)).actions,
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
    fun `new baseline session produces initial fetch with exclusive cursor`() {
        val plan = DshReconciler.plan(
            local = emptyMap(),
            baseline = mapOf("fresh" to 42L),
        )
        assertEquals(
            listOf(DshReconcileAction.InitialFetch(sessionId = "fresh", beforeSeq = 43L, maxMessages = 50)),
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
                DshReconcileAction.InitialFetch("a-new", beforeSeq = 8L, maxMessages = 25),
                DshReconcileAction.Backfill("b-gap", beforeSeq = 101L, maxMessages = 25),
                // c-synced：baseline 10 == local 9 + 1 → 恰落后一条，仍需回填（seq 10 不从流交付）
                DshReconcileAction.Backfill("c-synced", beforeSeq = 11L, maxMessages = 25),
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
