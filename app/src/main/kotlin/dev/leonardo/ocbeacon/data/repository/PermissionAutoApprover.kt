package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "PermissionAutoApprover"

/**
 * 管理持久化在 DataStore 中的权限自动批准规则。
 * 将传入的 [SseEvent.PermissionAsked] 事件与已保存规则进行匹配。
 *
 * C7（2026-08-26）：自动批准编排（目录解析 + 规则匹配 + respondPermission）
 * 从 EventDispatcher.maybeAutoApprovePermission 整体迁入——dispatcher 只在
 * PermissionAsked 分发点调用 [maybeAutoApprove]，不再持有 chatRepo/专属 scope。
 */
@Singleton
class PermissionAutoApprover @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val appScope: CoroutineScope,
    // 目录解析：Session.directory 取自 sessionHandler 内存态（查不到传空串，
    // directoryPattern="*" 的规则仍可匹配）
    private val sessionHandler: SessionEventHandler,
    // Provider 打破 PermissionAutoApprover → ChatRepository → EventDispatcher
    // → PermissionAutoApprover 的环（与原 EventDispatcher.chatRepoProvider 同款形状）
    private val chatRepoProvider: Provider<ChatRepository>,
) {
    companion object {
        private val KEY_RULES = stringSetPreferencesKey("permission_auto_approve_rules")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * #122（2026-08-18 接线；C7 迁入本类）：PermissionAsked 自动批准钩子。
     *
     * 规则匹配（AutoApproveRule.matches：toolName/sessionId/directoryPattern）
     * → 异步 respondPermission("once")。失败仅 WARN 日志——自动批准是尽力而为
     * 的增强，不阻塞事件主路径（用户仍可手动回复）。规则列表为空 =
     * [shouldAutoApprove] 恒 false，天然关闭。
     */
    fun maybeAutoApprove(event: SseEvent.PermissionAsked, serverId: String) {
        appScope.launch {
            try {
                val sessionDirectory = sessionHandler.sessions.value
                    .firstOrNull { it.id == event.sessionId }?.directory ?: ""
                if (!shouldAutoApprove(event, sessionDirectory)) return@launch
                AppLogger.i(TAG, "[auto-approve] rule matched: permission=" + event.permission + " sid=" + event.sessionId.take(12) + " dir=" + sessionDirectory + " — replying once")
                val ok = chatRepoProvider.get()
                    .respondPermission(serverId, event.sessionId, event.id, "once", sessionDirectory.takeIf { it.isNotBlank() })
                    .getOrDefault(false)
                if (!ok) {
                    AppLogger.w(TAG, "[auto-approve] respondPermission returned false (request may have expired): id=" + event.id)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLogger.w(TAG, "[auto-approve] failed: " + t.message)
            }
        }
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
