package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 收藏会话的离线快照。
 *
 * 持久化在 SettingsDataStore 中，以 [favoriteKey]（"serverId:sessionId"）为键，
 * 这样当服务器断开连接时，收藏的会话仍能显示在跨服务器收藏列表中——
 * 实时 [Session] 不可用，但标题/时间戳可恢复。
 *
 * 仅捕获收藏列表显示所需的字段；并非 [Session] 的完整副本。
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
        /** 从实时 [Session] 构建快照，捕获与显示相关的字段。 */
        fun from(session: Session): FavoriteSessionSnapshot = FavoriteSessionSnapshot(
            sessionId = session.id,
            title = session.title.orEmpty().ifBlank { session.id },
            created = session.time.created,
            updated = session.time.updated,
        )
    }
}

/**
 * 跨服务器场景下 (server, session) 对的唯一键。
 * 用作全局收藏顺序列表和快照映射的键。
 */
fun favoriteKey(serverId: String, sessionId: String): String = "$serverId:$sessionId"
