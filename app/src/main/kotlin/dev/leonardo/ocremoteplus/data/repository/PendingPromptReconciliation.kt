package dev.leonardo.ocremoteplus.data.repository

import dev.leonardo.ocremoteplus.domain.model.Message
import dev.leonardo.ocremoteplus.domain.model.ModelSelection
import dev.leonardo.ocremoteplus.domain.model.PromptPart
import kotlinx.serialization.Serializable

/**
 * Persisted record of an optimistic (not-yet-server-confirmed) prompt send.
 *
 * Stored by [PendingPromptRepository] so that pending prompts survive app restarts.
 * On next launch, [missingPendingPromptIds] reconciles them against the server's
 * authoritative message list to detect sends that were lost.
 */
@Serializable
data class PendingPromptRecord(
    val messageId: String,
    val sessionId: String,
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val variant: String? = null,
    val directory: String? = null,
    val createdAt: Long,
)

/**
 * Reconciliation pure function: decides which pending prompts are stale enough to
 * be considered lost and should be surfaced to the user as failed.
 *
 * Strategy — timestamp coverage:
 *  1. A pending whose [PendingPromptRecord.messageId] appears in [authoritative]
 *     is already confirmed → never missing.
 *  2. A pending older than [minimumAgeMs] **and** "covered" (the server has
 *     delivered any message with `time.created >= pending.createdAt`) is missing —
 *     the server has progressed past the send point yet never echoed the prompt.
 *  3. Otherwise the pending is retained (too fresh, or the server hasn't caught up).
 *
 * This is format-agnostic: unlike upstream v1.7.0 (which compares ULID id ranges),
 * it works with our `"pending-<uuid>"` ids because it keys off timestamps, not id
 * ordering — matching the existing confirm logic in MessageDataDelegate.
 *
 * @param pending candidate pending prompts to reconcile.
 * @param authoritative the server's current message list for the session.
 * @param now current epoch millis (for age calculation).
 * @param minimumAgeMs minimum age before a covered pending is declared missing.
 * @return the set of pending message ids considered lost.
 */
fun missingPendingPromptIds(
    pending: List<PendingPromptRecord>,
    authoritative: List<Message>,
    now: Long,
    minimumAgeMs: Long,
): Set<String> {
    if (pending.isEmpty()) return emptySet()
    val confirmedIds = authoritative.asSequence().map { it.id }.toSet()
    // "Covered" = the server delivered any message whose created timestamp is at
    // or after the pending's send time. Track via the max created timestamp so we
    // don't scan the whole authoritative list per pending record.
    val maxCreated = authoritative.maxOfOrNull { it.time.created } ?: return emptySet()
    return pending
        .asSequence()
        .filter { record ->
            record.messageId !in confirmedIds &&
                (now - record.createdAt) >= minimumAgeMs &&
                maxCreated >= record.createdAt
        }
        .map { it.messageId }
        .toSet()
}
