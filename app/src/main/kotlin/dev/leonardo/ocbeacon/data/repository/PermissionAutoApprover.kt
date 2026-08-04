package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理持久化在 DataStore 中的权限自动批准规则。
 * 将传入的 [SseEvent.PermissionAsked] 事件与已保存规则进行匹配。
 */
@Singleton
class PermissionAutoApprover @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_RULES = stringSetPreferencesKey("permission_auto_approve_rules")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** 从 DataStore 加载所有已保存的规则。 */
    suspend fun loadRules(): Set<AutoApproveRule> {
        return dataStore.data.map { prefs ->
            val jsonStrings = prefs[KEY_RULES] ?: emptySet()
            jsonStrings.mapNotNull { runCatching { json.decodeFromString<AutoApproveRule>(it) }.getOrNull() }.toSet()
        }.first()
    }

    /** 检查事件是否匹配任一已保存规则。 */
    suspend fun shouldAutoApprove(event: SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        val rules = loadRules()
        return rules.any { it.matches(event, sessionDirectory) }
    }

    /** 持久化新规则（用户选择了"总是"）。 */
    suspend fun addRule(rule: AutoApproveRule) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RULES] ?: emptySet()
            val ruleJson = json.encodeToString(rule)
            prefs[KEY_RULES] = existing + ruleJson
        }
    }

    /** 移除规则。 */
    suspend fun removeRule(rule: AutoApproveRule) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RULES] ?: emptySet()
            val ruleJson = json.encodeToString(rule)
            prefs[KEY_RULES] = existing - ruleJson
        }
    }

    /** 清除所有规则。 */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_RULES)
        }
    }
}
