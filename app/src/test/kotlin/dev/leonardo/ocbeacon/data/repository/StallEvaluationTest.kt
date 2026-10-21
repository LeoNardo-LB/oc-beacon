package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.SessionFSMState
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #562（会话停滞降级提示）：[evaluateStalled] 纯函数判定——单测钉死三分支
 * 语义边界（Busy+超阈值=入集；Idle/事件新鲜=不入；#191 waiting 打标窗内=不入）。
 */
class StallEvaluationTest {

    private fun busy(lastEventAt: Long) = SessionFSMState(
        core = SessionStatus.Busy,
        activity = null,
        lastEventAt = lastEventAt,
        lastCoreTransitionAt = lastEventAt,
    )

    private val idle = SessionFSMState.initial(now = 0L)

    @Test
    fun `Busy 且超阈值无事件 - 入停滞集`() {
        val now = 130_000L
        val states = mapOf("s1" to busy(lastEventAt = 0L))
        val out = evaluateStalled(states, emptyMap(), now, thresholdMs = 120_000L)
        assertEquals(setOf("s1"), out)
    }

    @Test
    fun `Busy 但事件在阈值内 - 不入集（正常流式_标题生成静默窗）`() {
        val now = 100_000L
        val states = mapOf("s1" to busy(lastEventAt = now - 70_000L)) // 70s < 120s
        val out = evaluateStalled(states, emptyMap(), now, thresholdMs = 120_000L)
        assertEquals(emptySet<String>(), out)
    }

    @Test
    fun `Idle 无论多久无事件 - 不入集`() {
        val now = 100_000L
        val states = mapOf("s1" to idle.copy(lastEventAt = 0L))
        val out = evaluateStalled(states, emptyMap(), now, thresholdMs = 120_000L)
        assertEquals(emptySet<String>(), out)
    }

    @Test
    fun `Busy 超阈值但 waiting 打标窗内 - 不入集（191 合法等待_待输入_子智能体）`() {
        val now = 130_000L
        val states = mapOf("s1" to busy(lastEventAt = 0L))
        val waiting = mapOf("s1" to now - 20_000L) // 20s 前确认 < 60s 窗
        val out = evaluateStalled(states, waiting, now, thresholdMs = 120_000L)
        assertEquals(emptySet<String>(), out)
    }

    @Test
    fun `Busy 超阈值且 waiting 打标过期 - 入集（窗口过后回归评估）`() {
        val now = 130_000L
        val states = mapOf("s1" to busy(lastEventAt = 0L))
        val waiting = mapOf("s1" to now - 80_000L) // 80s 前确认 > 60s 窗
        val out = evaluateStalled(states, waiting, now, thresholdMs = 120_000L)
        assertEquals(setOf("s1"), out)
    }

    @Test
    fun `waiting 打标属于他会话 - 不影响本会话入集`() {
        val now = 130_000L
        val states = mapOf("s1" to busy(lastEventAt = 0L))
        val waiting = mapOf("other" to now - 1_000L)
        val out = evaluateStalled(states, waiting, now, thresholdMs = 120_000L)
        assertEquals(setOf("s1"), out)
    }

    @Test
    fun `混合会话集 - 仅满足条件者入集（默认阈值=STALL_HINT_MS 120s 锚点）`() {
        val now = 0L
        val states = mapOf(
            "busyStalled" to busy(lastEventAt = -180_000L),   // 3min 无事件
            "busyFresh" to busy(lastEventAt = -10_000L),       // 10s
            "idleOld" to idle.copy(lastEventAt = -999_999L),
        )
        val out = evaluateStalled(states, emptyMap(), now)
        assertEquals(setOf("busyStalled"), out)
    }
}
