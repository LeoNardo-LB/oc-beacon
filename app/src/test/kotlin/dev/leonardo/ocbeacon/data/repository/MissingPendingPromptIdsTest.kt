package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [missingPendingPromptIds] — the reconciliation pure function that decides
 * which optimistic pending prompts are stale enough to be considered lost.
 *
 * Strategy: timestamp coverage. A pending prompt is "covered" when the server has
 * delivered any message whose created timestamp is at or after the pending's send
 * time — meaning the server has progressed past that point. If the pending is both
 * covered and older than [minimumAgeMs], it is declared missing.
 *
 * This differs from upstream v1.7.0 (which compares ULID id ranges) because our
 * pendingId is "pending-<uuid>" and is NOT in the same ordering space as server
 * ULIDs. Timestamp coverage is format-agnostic and matches the existing "confirm"
 * logic in MessageDataDelegate's combine pipeline.
 */
class MissingPendingPromptIdsTest {

    @Test
    fun `pending covered by server and expired is declared missing`() {
        val pending = pending("pending-1", createdAt = 1_000)
        // Server delivered a message created AFTER the pending send time → covered.
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
        // Server messages are all OLDER than the pending → not covered yet,
        // the pending may still be in flight.
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
        // The pending's own id appears in the authoritative list → already confirmed.
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
            message("01H_OLD", created = 2_000),   // covers expired + fresh, not uncovered? 
            message("01H_NEW", created = 19_000),  // covers expired, fresh, uncovered(6k<19k)
        )
        // uncovered created=6000, 01H_NEW created=19000 >= 6000 → covered.
        // So uncovered IS covered. To make a truly uncovered one, its createdAt must
        // exceed all authoritative created values.

        val trulyUncovered = pending("pending-future", createdAt = 50_000)
        val allPending = listOf(expired, fresh, uncovered, trulyUncovered)

        val result = missingPendingPromptIds(
            pending = allPending,
            authoritative = authoritative,
            now = 100_000,
            minimumAgeMs = 10_000,
        )

        // expired: age=99000>=10000, covered(2000>=1000,19000>=1000) → missing
        // fresh: age=82000>=10000, covered(19000>=18000) → missing
        // uncovered: age=94000>=10000, covered(19000>=6000) → missing
        // trulyUncovered: age=50000>=10000, NOT covered(no msg>=50000) → retained
        assertEquals(setOf("pending-expired", "pending-fresh", "pending-uncovered"), result)
    }

    // ---- helpers ----

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
