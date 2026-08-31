package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DshTokenUsage / DshSubagentTiming 派生逻辑单测（B：时长派生 / 累计 tokens）。
 *
 * 对齐官方 ui-subagent client.js:77-84 activityDuration（settled + active 进行段）与
 * tokenTotal（四桶求和）。
 */
class DshProjectionTest {

    @Test
    fun `token total sums four disjoint buckets`() {
        assertEquals(170L, DshTokenUsage(100, 50, 20, 0).total)
        assertEquals(0L, DshTokenUsage().total)
        assertEquals(1_000_000L, DshTokenUsage(uncachedInputTokens = 1_000_000L).total)
    }

    @Test
    fun `active duration without active is settled only`() {
        assertEquals(42L, DshSubagentTiming(settledMs = 42L).activeDurationMs)
    }

    @Test
    fun `active duration adds settled plus active segment`() {
        assertEquals(3000L, DshSubagentTiming(1500L, 1000L, 2500L).activeDurationMs)
    }

    @Test
    fun `active duration clamps negative segment to zero`() {
        // active.through < active.since（畸形/时钟回拨）→ 进行段归零，只保留 settled
        assertEquals(1500L, DshSubagentTiming(1500L, 2500L, 1000L).activeDurationMs)
    }
}
