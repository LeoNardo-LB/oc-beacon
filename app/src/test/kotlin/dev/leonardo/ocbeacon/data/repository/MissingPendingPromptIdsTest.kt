package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [missingPendingPromptIds] 的测试 —— 该对账纯函数决定
 * 哪些乐观的待处理 prompt 已足够陈旧、可被视为丢失。
 *
 * 策略：时间戳覆盖。当服务器已送达任何 created 时间戳
 * 不早于 pending 发送时间的消息时，该 pending prompt 即被 "覆盖"，
 * 意味着服务器已经越过该时间点。如果 pending 同时满足
 * 被覆盖且早于 [minimumAgeMs]，则判定为缺失。
 *
 * 这与上游 v1.7.0（比较 ULID id 范围）不同，因为我们的
 * pendingId 是 "pending-<uuid>"，与服务器 ULID 不在同一排序空间。
 * 时间戳覆盖与格式无关，并且与 MessageDataDelegate 的 combine
 * 管线中现有的 "confirm" 逻辑一致。
 */
class MissingPendingPromptIdsTest {

    @Test
    fun `pending covered by server and expired is declared missing`() {
        val pending = pending("pending-1", createdAt = 1_000)
        // 服务器已送达一条创建时间晚于 pending 发送时间的消息 → 已覆盖。
        val authoritative = listOf(
            message("01H_OLD", created = 500),
            message("01H_NEW", created = 2_000),
        )

        assertEquals(
            setOf("pending-1"),
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = authoritative,
                now = 20_000,
                minimumAgeMs = 10_000,
            ),
        )
    }

    @Test
    fun `pending covered by server but not yet expired is retained`() {
        val pending = pending("pending-1", createdAt = 15_000)
        val authoritative = listOf(message("01H_NEW", created = 20_000))

        assertTrue(
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = authoritative,
                now = 20_000,
                minimumAgeMs = 10_000,
            ).isEmpty(),
        )
    }

    @Test
    fun `pending expired but not covered by server is retained`() {
        val pending = pending("pending-1", createdAt = 5_000)
        // 服务器消息都早于 pending → 尚未被覆盖，
        // pending 可能仍在传输中。
        val authoritative = listOf(
            message("01H_A", created = 1_000),
            message("01H_B", created = 2_000),
        )

        assertTrue(
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = authoritative,
                now = 20_000,
                minimumAgeMs = 10_000,
            ).isEmpty(),
        )
    }

    @Test
    fun `confirmed pending is never declared missing even when covered and expired`() {
        val pending = pending("pending-1", createdAt = 1_000)
        // pending 自身的 id 出现在权威列表中 → 已被确认。
        val authoritative = listOf(message("pending-1", created = 1_000))

        assertTrue(
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = authoritative,
                now = 20_000,
                minimumAgeMs = 0,
            ).isEmpty(),
        )
    }

    @Test
    fun `empty pending list returns empty set`() {
        assertTrue(
            missingPendingPromptIds(
                pending = emptyList(),
                authoritative = listOf(message("01H_X", created = 1_000)),
                now = 100_000,
                minimumAgeMs = 10_000,
            ).isEmpty(),
        )
    }

    @Test
    fun `multiple pendings are reconciled independently`() {
        val expired = pending("pending-expired", createdAt = 1_000)
        val fresh = pending("pending-fresh", createdAt = 18_000)
        val uncovered = pending("pending-uncovered", createdAt = 6_000)
        val authoritative = listOf(
            message("01H_OLD", created = 2_000),   // 覆盖 expired + fresh，未覆盖 uncovered？
            message("01H_NEW", created = 19_000),  // 覆盖 expired、fresh、uncovered（6k<19k）
        )
        // uncovered created=6000，01H_NEW created=19000 >= 6000 → 已覆盖。
        // 因此 uncovered 实际上已被覆盖。要构造真正未被覆盖的项，其 createdAt 必须
        // 超过所有权威 created 值。

        val trulyUncovered = pending("pending-future", createdAt = 50_000)
        val allPending = listOf(expired, fresh, uncovered, trulyUncovered)

        val result = missingPendingPromptIds(
            pending = allPending,
            authoritative = authoritative,
            now = 100_000,
            minimumAgeMs = 10_000,
        )

        // expired：age=99000>=10000，covered(2000>=1000,19000>=1000) → missing
        // fresh：age=82000>=10000，covered(19000>=18000) → missing
        // uncovered：age=94000>=10000，covered(19000>=6000) → missing
        // trulyUncovered：age=50000>=10000，未覆盖（无 msg>=50000）→ 保留
        assertEquals(setOf("pending-expired", "pending-fresh", "pending-uncovered"), result)
    }

    // ---- 辅助函数 ----

    private fun pending(id: String, createdAt: Long) = PendingPromptRecord(
        messageId = id,
        sessionId = "session",
        parts = listOf(PromptPart(type = "text", text = "prompt")),
        createdAt = createdAt,
    )

    private fun message(id: String, created: Long): Message =
        Message.User(
            id = id,
            sessionId = "session",
            time = TimeInfo(created = created),
        )
}
