package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchHit
import org.junit.Assert.assertEquals
import org.junit.Test

/** #393：搜索内容命中组内「去重 + 角色交错」回归。 */
class SearchHitOrderingTest {

    private fun hit(id: String, role: String, rank: Double?) = ContentSearchHit(
        sessionId = "s",
        messageId = id,
        role = role,
        snippet = id,
        created = 0L,
        rank = rank,
    )

    @Test
    fun `user 密集列表交错出 AI 行`() {
        val out = interleaveSearchHits(
            listOf(
                hit("u1", "user", 0.1), hit("u2", "user", 0.2), hit("u3", "user", 0.3),
                hit("a1", "assistant", 1.0),
            ),
        )
        assertEquals(listOf("u1", "a1", "u2", "u3"), out.map { it.messageId })
    }

    @Test
    fun `同消息多 part 去重保留最优 rank`() {
        val out = interleaveSearchHits(
            listOf(
                hit("a1", "assistant", 5.0), hit("a1", "assistant", 1.0), hit("u1", "user", 2.0),
            ),
        )
        assertEquals(listOf("u1", "a1"), out.map { it.messageId })
        assertEquals(1.0, out.first { it.messageId == "a1" }.rank!!, 0.0)
    }

    @Test
    fun `单角色列表保持 rank 序`() {
        val out = interleaveSearchHits(
            listOf(hit("u2", "user", 0.9), hit("u1", "user", 0.1)),
        )
        assertEquals(listOf("u1", "u2"), out.map { it.messageId })
    }
}
