package dev.leonardo.ocremoteplus.domain.model

import kotlinx.serialization.Serializable

/**
 * Offline snapshot of a favorited session.
 *
 * Persisted in SettingsDataStore keyed by [favoriteKey] ("serverId:sessionId") so that a
 * favorited session can still be shown in the cross-server favorites list when its server
 * is disconnected — the live [Session] is unavailable but title/timestamps are recoverable.
 *
 * Captures only the fields required for the favorites list display; it is intentionally not
 * a full copy of [Session].
 *
 * @see CrossServerSessionItem
 */
@Serializable
data class FavoriteSessionSnapshot(
    val sessionId: String,
    val title: String,
    val created: Long,
    val updated: Long,
) {
    companion object {
        /** Build a snapshot from a live [Session], capturing display-relevant fields. */
        fun from(session: Session): FavoriteSessionSnapshot = FavoriteSessionSnapshot(
            sessionId = session.id,
            title = session.title.orEmpty().ifBlank { session.id },
            created = session.time.created,
            updated = session.time.updated,
        )
    }
}

/**
 * Cross-server unique key for a (server, session) pair.
 * Used to key the global favorite order list and the snapshot map.
 */
fun favoriteKey(serverId: String, sessionId: String): String = "$serverId:$sessionId"
