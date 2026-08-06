package dev.leonardo.ocbeacon.data.repository

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

private const val TAG_DIAG = "TagDiag"

private val tagJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val tagListSerializer = ListSerializer(Tag.serializer())
private val assignmentMapSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

private const val SESSION_TAGS_PREFIX = "session_tags_"
private const val SESSION_TAG_ASSIGNMENTS_PREFIX = "session_tag_assignments_"
/** 旧收藏 key（迁移源）—— SettingsDataStoreFavorites.kt 历史格式：stringSetPreferencesKey("favorite_sessions_" + serverId)。 */
private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"

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

/** 该服务器的标签集（不含内置收藏标签）。 */
fun SettingsDataStore.sessionTags(serverId: String): Flow<List<Tag>> =
    dataStore.data.map { prefs ->
        val json = prefs[tagsKey(serverId)]
        val tags = if (json.isNullOrBlank()) emptyList()
        else runCatching { tagJson.decodeFromString(tagListSerializer, json) }.getOrDefault(emptyList())
        tags.filter { it.type != TagType.FAVORITE }
    }

/** 统一分配 map（sessionId → tagIds，含内置收藏标签）。 */
fun SettingsDataStore.sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
    dataStore.data.map { prefs ->
        val json = prefs[assignmentsKey(serverId)]
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { tagJson.decodeFromString(assignmentMapSerializer, json) }.getOrDefault(emptyMap())
    }

suspend fun SettingsDataStore.addSessionTag(serverId: String, tag: Tag) {
    dataStore.edit { prefs ->
        val current = prefs[tagsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()
        prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tag.id } + tag)
    }
}

suspend fun SettingsDataStore.updateSessionTag(serverId: String, tag: Tag) = addSessionTag(serverId, tag)

suspend fun SettingsDataStore.removeSessionTag(serverId: String, tagId: String) {
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

suspend fun SettingsDataStore.setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) {
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

suspend fun SettingsDataStore.removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) {
    dataStore.edit { prefs ->
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val updated = assignments[sessionId].orEmpty().filterNot { it == tagId }
        val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
        prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
    }
}

suspend fun SettingsDataStore.toggleFavorite(serverId: String, sessionId: String) {
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
 * 收藏会话 id（从统一分配 map 派生）。
 * 首次读取时若统一 map 为空但存在旧 `favorite_sessions_*` stringSet 数据，则一次性迁移。
 *
 * 替换原 [SettingsDataStoreFavorites] 中的旧实现（直接读 stringSet）——
 * 新模型以 FAVORITE_TAG_ID 作为内置标签写入统一分配 map，收藏与用户标签共用一套数据。
 */
fun SettingsDataStore.favoriteSessionIds(serverId: String): Flow<Set<String>> =
    dataStore.data.map { prefs ->
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val fromAssignments = assignments.filterValues { FAVORITE_TAG_ID in it }.keys
        // 迁移：旧独立收藏 key（stringSet）→ 内置标签分配（一次性，写入后下次直接走 assignments）
        val legacy = prefs[legacyFavoriteKey(serverId)]
        if (legacy != null && fromAssignments.isEmpty() && legacy.isNotEmpty()) {
            dataStore.edit { p ->
                val cur = p[assignmentsKey(serverId)]?.let {
                    runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
                } ?: emptyMap()
                p[assignmentsKey(serverId)] = tagJson.encodeToString(
                    assignmentMapSerializer,
                    legacy.fold(cur) { acc, sid -> acc + (sid to (acc[sid].orEmpty() + FAVORITE_TAG_ID).distinct()) }
                )
                // 迁移成功后删源 key，保证幂等：否则用户取消全部收藏后 fromAssignments 重新变空，
                // 迁移条件再次满足会导致已取消的收藏被重新迁移"复活"（见 SettingsDataStoreTagsTest
                // `favoriteSessionIds migrate then unfavorite all does not resurrect`）。
                p.remove(legacyFavoriteKey(serverId))
            }
            AppLogger.d(TAG_DIAG, "[favoriteMigrate] server=$serverId count=${legacy.size}")
            legacy
        } else {
            fromAssignments
        }
    }
