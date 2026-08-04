package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.SessionCategory
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

    /**
     * 全局用户定义的会话类别列表。
     */
    fun sessionCategories(): Flow<List<SessionCategory>>

    /**
     * 按服务器划分的 sessionId → categoryId 分配。
     */
    fun sessionCategoryAssignments(serverId: String): Flow<Map<String, String>>

    /** 新增或替换一个类别。 */
    suspend fun addSessionCategory(category: SessionCategory)

    /** 按 id 移除一个类别。 */
    suspend fun removeSessionCategory(categoryId: String)

    /** 为指定服务器将会话分配到某个类别。 */
    suspend fun assignSessionCategory(serverId: String, sessionId: String, categoryId: String)

    /** 为指定服务器移除会话的类别分配。 */
    suspend fun unassignSessionCategory(serverId: String, sessionId: String)

    // ============ 跨服务器会话收藏 ============

    /** 某台服务器上被收藏的会话 id。 */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>>

    /** 全局跨服务器收藏顺序——"serverId:sessionId" 键的列表。 */
    val crossServerFavoriteOrder: Flow<List<String>>

    /** 以 "serverId:sessionId" 为键的离线快照。 */
    val favoriteSessionSnapshots: Flow<Map<String, FavoriteSessionSnapshot>>

    /** 将某台服务器上的某个会话加入收藏，并持久化其离线快照。 */
    suspend fun addFavoriteSession(serverId: String, sessionId: String, snapshot: FavoriteSessionSnapshot)

    /** 将某台服务器上的某个会话从收藏移除，并清除其快照。 */
    suspend fun removeFavoriteSession(serverId: String, sessionId: String)

    /** 替换整个跨服务器收藏顺序。 */
    suspend fun setCrossServerFavoriteOrder(order: List<String>)

    /** 在跨服务器顺序列表中插入或移除单个收藏键。 */
    suspend fun setCrossServerFavoriteOrderItem(key: String, favorite: Boolean)

    /** 为 (server, session) 对保存或替换快照。 */
    suspend fun saveFavoriteSessionSnapshot(serverId: String, sessionId: String, snapshot: FavoriteSessionSnapshot)

    /** 清除 (server, session) 对的快照。 */
    suspend fun clearFavoriteSessionSnapshot(serverId: String, sessionId: String)
}
