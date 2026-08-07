package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * 应用设置的领域层接口。
 * 与 spec §4.1.1 对齐。
 * 由 Data 层在 Phase 3 实现。
 */
interface SettingsRepository {

    /**
     * 观察聚合后的应用设置。
     */
    fun getSettingsFlow(): Flow<AppSettings>

    /**
     * 更新应用设置。
     */
    suspend fun updateSettings(settings: AppSettings): Result<Unit>

    /**
     * 观察某台服务器的隐藏 model 键集合。
     * 键格式："providerId:modelId"。
     */
    fun hiddenModels(serverId: String): Flow<Set<String>>

    /**
     * 设置某个 model 的可见性。
     * @param visible false 时将该 model 加入隐藏集合。
     */
    suspend fun setModelVisibility(serverId: String, providerId: String, modelId: String, visible: Boolean)

    // ============ 会话标签 ============

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

    /** 某台服务器上被收藏的会话 id（从统一分配 map 派生，首次读取时迁移旧 favorite_sessions_* stringSet）。 */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>>

    /** 切换指定 (serverId, sessionId) 对的收藏状态。 */
    suspend fun toggleFavorite(serverId: String, sessionId: String)

    // ============ 会话已读（未读提示） ============

    /** 该服务器各会话的最后已读时间（sessionId → 最后消费的完成消息 completed）。 */
    fun sessionReadTimes(serverId: String): Flow<Map<String, Long>>

    /** 该服务器的"一键已读"时间戳（服务器 completed）：此前的所有回复都算已读。 */
    fun allReadAt(serverId: String): Flow<Long>

    /** 一键已读：记录全局已读位置（已知会话最后完成消息的 completed，服务器时刻），消除所有小红点。 */
    suspend fun markAllSessionsRead(serverId: String, globalMax: Long)

    /** 将会话标记为已读（记录最后消费的完成消息 completed，服务器时刻）。 */
    suspend fun markSessionRead(serverId: String, sessionId: String, completedTs: Long)

    /** 一次性迁移：清空已读标记（readTimes/allReadAt）——值域从客户端 now 变为服务器 completed，旧值不可比。幂等。 */
    suspend fun runUnreadStateV2Migration()
}
