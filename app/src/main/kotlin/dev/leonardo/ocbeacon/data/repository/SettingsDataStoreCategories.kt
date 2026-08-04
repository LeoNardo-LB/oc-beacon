package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============ 会话分类——全局列表（JSON 数组）+ 按服务器的分配（JSON map） ============

private val SESSION_CATEGORIES_KEY = stringPreferencesKey("session_categories")
private const val SESSION_CATEGORY_ASSIGNMENTS_PREFIX = "session_category_assignments_"

private val categoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val categoryListSerializer = ListSerializer(SessionCategory.serializer())
private val assignmentMapSerializer = MapSerializer(String.serializer(), String.serializer())

private fun sessionCategoryAssignmentsKey(serverId: String) =
    stringPreferencesKey(SESSION_CATEGORY_ASSIGNMENTS_PREFIX + serverId)

/** 用户自定义的会话分类全局列表。 */
val SettingsDataStore.sessionCategories: Flow<List<SessionCategory>>
    get() = dataStore.data.map { preferences ->
        val json = preferences[SESSION_CATEGORIES_KEY]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { categoryJson.decodeFromString(categoryListSerializer, json) }
                .getOrDefault(emptyList())
        }
    }

/** 按服务器的 会话→分类 id 分配。 */
fun SettingsDataStore.sessionCategoryAssignments(serverId: String): Flow<Map<String, String>> =
    dataStore.data.map { preferences ->
        val json = preferences[sessionCategoryAssignmentsKey(serverId)]
        if (json.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching { categoryJson.decodeFromString(assignmentMapSerializer, json) }
                .getOrDefault(emptyMap())
        }
    }

/** 添加或替换分类（按 id 匹配）。 */
suspend fun SettingsDataStore.addSessionCategory(category: SessionCategory) {
    dataStore.edit { preferences ->
        val current = preferences[SESSION_CATEGORIES_KEY]?.let {
            runCatching { categoryJson.decodeFromString(categoryListSerializer, it) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val updated = current.filterNot { it.id == category.id } + category
        preferences[SESSION_CATEGORIES_KEY] = categoryJson.encodeToString(categoryListSerializer, updated)
    }
}

/** 移除分类并清除引用它的所有分配。 */
suspend fun SettingsDataStore.removeSessionCategory(categoryId: String) {
    dataStore.edit { preferences ->
        val current = preferences[SESSION_CATEGORIES_KEY]?.let {
            runCatching { categoryJson.decodeFromString(categoryListSerializer, it) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        preferences[SESSION_CATEGORIES_KEY] =
            categoryJson.encodeToString(categoryListSerializer, current.filterNot { it.id == categoryId })
    }
}

/** 为给定服务器将一个会话分配到某分类。 */
suspend fun SettingsDataStore.assignSessionCategory(serverId: String, sessionId: String, categoryId: String) {
    val prefsKey = sessionCategoryAssignmentsKey(serverId)
    dataStore.edit { preferences ->
        val current = preferences[prefsKey]?.let {
            runCatching { categoryJson.decodeFromString(assignmentMapSerializer, it) }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
        preferences[prefsKey] =
            categoryJson.encodeToString(assignmentMapSerializer, current + (sessionId to categoryId))
    }
}

/** 移除给定服务器中某会话的分类分配。 */
suspend fun SettingsDataStore.unassignSessionCategory(serverId: String, sessionId: String) {
    val prefsKey = sessionCategoryAssignmentsKey(serverId)
    dataStore.edit { preferences ->
        val current = preferences[prefsKey]?.let {
            runCatching { categoryJson.decodeFromString(assignmentMapSerializer, it) }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
        preferences[prefsKey] =
            categoryJson.encodeToString(assignmentMapSerializer, current - sessionId)
    }
}
