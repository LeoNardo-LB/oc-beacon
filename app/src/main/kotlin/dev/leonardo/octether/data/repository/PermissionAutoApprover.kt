package dev.leonardo.octether.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.octether.domain.model.AutoApproveRule
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages permission auto-approve rules persisted in DataStore.
 * Checks incoming [SseEvent.PermissionAsked] events against saved rules.
 */
@Singleton
class PermissionAutoApprover @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_RULES = stringSetPreferencesKey("permission_auto_approve_rules")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Load all saved rules from DataStore. */
    suspend fun loadRules(): Set<AutoApproveRule> {
        return dataStore.data.map { prefs ->
            val jsonStrings = prefs[KEY_RULES] ?: emptySet()
            jsonStrings.mapNotNull { runCatching { json.decodeFromString<AutoApproveRule>(it) }.getOrNull() }.toSet()
        }.first()
    }

    /** Check if an event matches any saved rule. */
    suspend fun shouldAutoApprove(event: SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        val rules = loadRules()
        return rules.any { it.matches(event, sessionDirectory) }
    }

    /** Persist a new rule (user chose "always"). */
    suspend fun addRule(rule: AutoApproveRule) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RULES] ?: emptySet()
            val ruleJson = json.encodeToString(rule)
            prefs[KEY_RULES] = existing + ruleJson
        }
    }

    /** Remove a rule. */
    suspend fun removeRule(rule: AutoApproveRule) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RULES] ?: emptySet()
            val ruleJson = json.encodeToString(rule)
            prefs[KEY_RULES] = existing - ruleJson
        }
    }

    /** Clear all rules. */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_RULES)
        }
    }
}
