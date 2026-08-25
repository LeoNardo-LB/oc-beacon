package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.model.TagType
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话标签持久化存储（C5 存储归属拆分，自 SettingsDataStore 迁出——纯代码移动，
 * 同一 DataStore 实例、同键名、同序列化格式，**零数据迁移**）。
 *
 * 职责：用户标签 CRUD、统一分配 map（sessionId → tagIds）、内置收藏标签
 * （[builtinFavoriteTag]，每服务器固定一个不可删改）与旧收藏 key 的一次性迁移
 * （#137 D2-L59：迁移显式触发，由 SessionListViewModel init 调用）。
 * UI 层经 domain 接口 [dev.leonardo.ocbeacon.domain.repository.SessionTagRepository] 访问。
 */
@Singleton
class SessionTagStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private const val TAG_DIAG = "TagDiag"
        private const val SESSION_TAGS_PREFIX = "session_tags_"
        private const val SESSION_TAG_ASSIGNMENTS_PREFIX = "session_tag_assignments_"
        /** 旧收藏 key（迁移源）—— SettingsDataStoreFavorites.kt 历史格式：stringSetPreferencesKey("favorite_sessions_" + serverId)。 */
        private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"

        private val tagJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val tagListSerializer = ListSerializer(Tag.serializer())
        private val assignmentMapSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

        private fun tagsKey(serverId: String) = stringPreferencesKey(SESSION_TAGS_PREFIX + serverId)
        private fun assignmentsKey(serverId: String) = stringPreferencesKey(SESSION_TAG_ASSIGNMENTS_PREFIX + serverId)
        private fun legacyFavoriteKey(serverId: String) = stringSetPreferencesKey(FAVORITE_SESSIONS_PREFIX + serverId)

        /** 内置收藏标签（每服务器固定一个，不可删改）。 */
        fun builtinFavoriteTag(): Tag = Tag(
            id = FAVORITE_TAG_ID,
            name = "收藏",
            color = "amber",
            icon = "star",
            type = TagType.FAVORITE,
            createdAt = 0,
        )
    }

    /** 该服务器的标签集（不含内置收藏标签）。 */
    fun sessionTags(serverId: String): Flow<List<Tag>> =
        dataStore.data.map { prefs ->
            val json = prefs[tagsKey(serverId)]
            val tags = if (json.isNullOrBlank()) emptyList()
            else runCatching { tagJson.decodeFromString(tagListSerializer, json) }.getOrDefault(emptyList())
            tags.filter { it.type != TagType.FAVORITE }
        }

    /** 统一分配 map（sessionId → tagIds，含内置收藏标签）。 */
    fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
        dataStore.data.map { prefs ->
            val json = prefs[assignmentsKey(serverId)]
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { tagJson.decodeFromString(assignmentMapSerializer, json) }.getOrDefault(emptyMap())
        }

    suspend fun addSessionTag(serverId: String, tag: Tag) {
        dataStore.edit { prefs ->
            val current = prefs[tagsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tag.id } + tag)
        }
    }

    suspend fun updateSessionTag(serverId: String, tag: Tag) = addSessionTag(serverId, tag)

    suspend fun removeSessionTag(serverId: String, tagId: String) {
        dataStore.edit { prefs ->
            val current = prefs[tagsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tagId })
            // 同一 edit：清理所有会话的该标签分配（原子）
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            if (assignments.values.any { tagId in it }) {
                prefs[assignmentsKey(serverId)] = tagJson.encodeToString(
                    assignmentMapSerializer,
                    assignments.mapValues { (_, ids) -> ids.filterNot { it == tagId } }
                )
            }
        }
        AppLogger.d(TAG_DIAG, "[removeTag] done server=$serverId tag=$tagId")
    }

    suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) {
        dataStore.edit { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val current = assignments[sessionId].orEmpty().filter { it == FAVORITE_TAG_ID } // 保留收藏，只替换 USER 标签
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(
                assignmentMapSerializer,
                assignments + (sessionId to (current + tagIds).distinct())
            )
        }
        AppLogger.d(TAG_DIAG, "[setTags] done server=$serverId session=$sessionId tags=$tagIds")
    }

    suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) {
        dataStore.edit { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val updated = assignments[sessionId].orEmpty().filterNot { it == tagId }
            val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
        }
    }

    suspend fun toggleFavorite(serverId: String, sessionId: String) {
        dataStore.edit { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val current = assignments[sessionId].orEmpty()
            val updated = if (FAVORITE_TAG_ID in current) {
                current.filterNot { it == FAVORITE_TAG_ID }
            } else {
                current + FAVORITE_TAG_ID
            }
            val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
        }
    }

    /**
     * 收藏会话 id（从统一分配 map 派生）——纯读取，无副作用。
     * #137（D2-L59）：旧实现把一次性迁移（dataStore.edit）藏在 flow map 内——
     * 每次数据发射都检查并可能写库（隐蔽副作用）；迁移改为显式
     * [migrateLegacyFavoritesIfNeeded]，由使用方（SessionListViewModel）在 init 触发。
     */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>> =
        dataStore.data.map { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            assignments.filterValues { FAVORITE_TAG_ID in it }.keys
        }

    /**
     * #137（D2-L59）：旧独立收藏 key（stringSet）→ 内置标签分配的一次性迁移。
     * 幂等：迁移成功后删除源 key——否则用户取消全部收藏后 fromAssignments 重新变空，
     * 迁移条件再次满足会导致已取消的收藏被重新迁移"复活"（见 SessionTagStoreTest
     * `favoriteSessionIds migrate then unfavorite all does not resurrect`）。
     */
    suspend fun migrateLegacyFavoritesIfNeeded(serverId: String) {
        dataStore.edit { p ->
            val legacy = p[legacyFavoriteKey(serverId)] ?: return@edit
            if (legacy.isEmpty()) {
                p.remove(legacyFavoriteKey(serverId))
                return@edit
            }
            val cur = p[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            if (cur.values.any { FAVORITE_TAG_ID in it }) {
                // 已有收藏分配——不再迁移（避免覆盖用户新状态）
                p.remove(legacyFavoriteKey(serverId))
                return@edit
            }
            p[assignmentsKey(serverId)] = tagJson.encodeToString(
                assignmentMapSerializer,
                legacy.fold(cur) { acc, sid -> acc + (sid to (acc[sid].orEmpty() + FAVORITE_TAG_ID).distinct()) }
            )
            p.remove(legacyFavoriteKey(serverId))
        }
        AppLogger.d(TAG_DIAG, "[favoriteMigrate] server=" + serverId)
    }
}
