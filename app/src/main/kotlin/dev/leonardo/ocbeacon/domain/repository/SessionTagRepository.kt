package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * 会话标签的领域层接口（C5 存储归属拆分：自 SettingsRepository 迁出）。
 * 实现归属：由 data 层 [dev.leonardo.ocbeacon.data.repository.SessionTagRepositoryImpl]
 * 委托 [dev.leonardo.ocbeacon.data.repository.SessionTagStore]（UI 层必须经 domain 访问）。
 */
interface SessionTagRepository {

    /** 该服务器的用户标签集（不含内置收藏标签）。 */
    fun sessionTags(serverId: String): Flow<List<Tag>>

    /** 按服务器划分的 sessionId → tagIds 分配（含内置收藏标签）。 */
    fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>>

    /** 新增或替换一个用户标签。 */
    suspend fun addSessionTag(serverId: String, tag: Tag)

    /** 更新一个已存在的用户标签（按 id 替换）。 */
    suspend fun updateSessionTag(serverId: String, tag: Tag)

    /** 按 id 移除一个标签，并原子清理所有会话上该标签的分配。 */
    suspend fun removeSessionTag(serverId: String, tagId: String)

    /** 替换指定会话上的用户标签集（保留内置收藏标签）。 */
    suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>)

    /** 移除指定会话上的某个标签分配（不删除标签本身）。 */
    suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String)

    // ============ 会话收藏（基于内置收藏标签派生） ============

    /** 某台服务器上被收藏的会话 id（从统一分配 map 派生，纯读取）。 */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>>

    /** #137（D2-L59）：旧 favorite_sessions_* stringSet → 统一分配 map 的一次性迁移（显式触发）。 */
    suspend fun migrateLegacyFavoritesIfNeeded(serverId: String)

    /** 切换指定 (serverId, sessionId) 对的收藏状态。 */
    suspend fun toggleFavorite(serverId: String, sessionId: String)
}
