package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #260 question 轮询分层节拍回归（2026-08-30）。
 *
 * 背景：每 30s 轮询轮内做「默认 location + 全部项目目录」fan-out，
 * 项目数随服务器历史增长（实证 10 projects → 9 请求/30s，轮内 15-20ms
 * 密集风暴，logcat 4 分钟 83 次）。修复：默认 location 每 轮必查；
 * 项目目录 fan-out 仅在 [QuestionPollPlanner.FANOUT_ROUNDS] 整除轮执行。
 *
 * 缝隙：纯节拍逻辑抽为 planner 直接驱动——不依赖 Android Service。
 */
class QuestionPollPlannerTest {

    @Test
    fun `round0 必须全扫——冷启动纯REST路径首轮可见 pending form（2026-08-08 E2E-C 不变量）`() {
        assertTrue(QuestionPollPlanner.isFanOutRound(0))
    }

    @Test
    fun `稳态轮 1 到 9 不做 fan-out——消除 15-20ms 风暴窗口`() {
        for (round in 1 until QuestionPollPlanner.FANOUT_ROUNDS) {
            assertFalse("round=$round 不应 fan-out", QuestionPollPlanner.isFanOutRound(round))
        }
    }

    @Test
    fun `fan-out 节拍按周期维持——兜底不随分层失效`() {
        for (k in 1..5) {
            val round = k * QuestionPollPlanner.FANOUT_ROUNDS
            assertTrue("round=$round 应 fan-out", QuestionPollPlanner.isFanOutRound(round))
        }
    }

    @Test
    fun `前 100 轮 fan-out 频率不超过每周期一次`() {
        val fanOuts = (0 until 100).count { QuestionPollPlanner.isFanOutRound(it) }
        val expected = 100 / QuestionPollPlanner.FANOUT_ROUNDS + 1 // round 0 + 各周期首
        assertTrue("fan-out 轮数 $fanOuts 应 <= $expected", fanOuts <= expected)
    }
}
