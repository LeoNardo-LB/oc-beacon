package dev.leonardo.ocbeacon.domain.tracker

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #134（D2-L39）：TokenStatsTracker.update 并发安全。
 * 原实现裸读-改-写（_stats.value = _stats.value.block()）——并发 update
 * 丢更新；StateFlow.update CAS 循环保证原子合并。
 */
class TokenStatsTrackerConcurrencyTest {

    @Test
    fun `concurrent updates do not lose increments`() = runTest {
        val tracker = TokenStatsTracker()
        val perCoroutine = 500
        val coroutines = 8

        coroutineScope {
            List(coroutines) {
                async {
                    repeat(perCoroutine) {
                        tracker.update { copy(totalInputTokens = totalInputTokens + 1) }
                    }
                }
            }.awaitAll()
        }

        assertEquals(
            "并发 update 不得丢计数",
            perCoroutine * coroutines,
            tracker.stats.value.totalInputTokens
        )
    }

    @Test
    fun `concurrent mixed updates merge atomically`() = runTest {
        val tracker = TokenStatsTracker()
        val perCoroutine = 300

        coroutineScope {
            List(6) { idx ->
                async {
                    repeat(perCoroutine) {
                        if (idx % 2 == 0) {
                            tracker.update { copy(totalInputTokens = totalInputTokens + 1) }
                        } else {
                            tracker.update { copy(totalOutputTokens = totalOutputTokens + 1) }
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(perCoroutine * 3, tracker.stats.value.totalInputTokens)
        assertEquals(perCoroutine * 3, tracker.stats.value.totalOutputTokens)
    }

    @Test
    fun `update preserves other fields`() {
        val tracker = TokenStatsTracker()
        tracker.update { copy(totalCost = 42.0) }
        tracker.update { copy(totalInputTokens = 7) }
        assertEquals(42.0, tracker.stats.value.totalCost, 0.0)
        assertEquals(7, tracker.stats.value.totalInputTokens)
    }
}
