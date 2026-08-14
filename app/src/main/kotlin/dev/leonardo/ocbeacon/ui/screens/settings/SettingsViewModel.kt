package dev.leonardo.ocbeacon.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.UpdateSettingsUseCase
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    private val autoApprover: PermissionAutoApprover
) : ViewModel() {

    val settings: StateFlow<AppSettings> = getSettingsFlowUseCase()
        .stateIn(viewModelScope, WhileSubscribed5s, AppSettings())

    // --- 便捷属性（从聚合设置 flow 映射而来） ---

    val appLanguage = settings.map { it.appLanguage }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val appTheme = settings.map { it.appTheme }.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val dynamicColor = settings.map { it.dynamicColor }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val chatFontSize = settings.map { it.chatFontSize }.stateIn(viewModelScope, SharingStarted.Eagerly, "medium")
    val chatDensity = settings.map { it.chatDensity }.stateIn(viewModelScope, SharingStarted.Eagerly, "normal")
    val notificationsEnabled = settings.map { it.notificationsEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val initialMessageCount = settings.map { it.initialMessageCount }.stateIn(viewModelScope, SharingStarted.Eagerly, 50)
    val recentDirectoryCount = settings.map { it.recentDirectoryCount }.stateIn(viewModelScope, SharingStarted.Eagerly, 20)
    val confirmBeforeSend = settings.map { it.confirmBeforeSend }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val amoledDark = settings.map { it.amoledDark }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val compactMessages = settings.map { it.compactMessages }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val collapseTools = settings.map { it.collapseTools }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val expandReasoning = settings.map { it.expandReasoning }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hapticFeedback = settings.map { it.hapticFeedback }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val reconnectMode = settings.map { it.reconnectMode }.stateIn(viewModelScope, SharingStarted.Eagerly, "normal")
    val keepScreenOn = settings.map { it.keepScreenOn }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val compressImageAttachments = settings.map { it.compressImageAttachments }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val imageAttachmentMaxLongSide = settings.map { it.imageAttachmentMaxLongSide }.stateIn(viewModelScope, SharingStarted.Eagerly, 1440)
    val imageAttachmentWebpQuality = settings.map { it.imageAttachmentWebpQuality }.stateIn(viewModelScope, SharingStarted.Eagerly, 60)
    val silentNotifications = settings.map { it.silentNotifications }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val terminalFontSize = settings.map { it.terminalFontSize }.stateIn(viewModelScope, SharingStarted.Eagerly, 13f)

    // --- 权限自动批准规则 ---
    private val _rulesRefreshTrigger = MutableStateFlow(0)
    val autoApproveRules: StateFlow<List<AutoApproveRule>> = _rulesRefreshTrigger
        .map { autoApprover.loadRules() }
        .map { it.toList().sortedByDescending { rule -> rule.createdAt } }
        .stateIn(viewModelScope, WhileSubscribed5s, emptyList())

    fun deletePermissionRule(rule: AutoApproveRule) {
        viewModelScope.launch {
            autoApprover.removeRule(rule)
            _rulesRefreshTrigger.value += 1
        }
    }

    // --- Setters ---

    fun setLanguage(languageCode: String) {
        updateSetting { it.copy(appLanguage = languageCode) }
    }

    fun setTheme(theme: String) {
        updateSetting { it.copy(appTheme = theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        updateSetting { it.copy(dynamicColor = enabled) }
    }

    fun setChatFontSize(size: String) {
        updateSetting { it.copy(chatFontSize = size) }
    }

    fun setChatDensity(value: String) {
        updateSetting { it.copy(chatDensity = value) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        updateSetting { it.copy(notificationsEnabled = enabled) }
    }

    fun setInitialMessageCount(count: Int) {
        updateSetting { it.copy(initialMessageCount = count) }
    }

    fun setRecentDirectoryCount(count: Int) {
        updateSetting { it.copy(recentDirectoryCount = count) }
    }

    fun setConfirmBeforeSend(enabled: Boolean) {
        updateSetting { it.copy(confirmBeforeSend = enabled) }
    }

    fun setAmoledDark(enabled: Boolean) {
        updateSetting { it.copy(amoledDark = enabled) }
    }

    fun setCompactMessages(enabled: Boolean) {
        updateSetting { it.copy(compactMessages = enabled) }
    }

    fun setCollapseTools(enabled: Boolean) {
        updateSetting { it.copy(collapseTools = enabled) }
    }

    fun setExpandReasoning(enabled: Boolean) {
        updateSetting { it.copy(expandReasoning = enabled) }
    }

    val showTurnDividers = settings.map { it.showTurnDividers }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowTurnDividers(enabled: Boolean) {
        updateSetting { it.copy(showTurnDividers = enabled) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        updateSetting { it.copy(hapticFeedback = enabled) }
    }

    fun setReconnectMode(mode: String) {
        updateSetting { it.copy(reconnectMode = mode) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        updateSetting { it.copy(keepScreenOn = enabled) }
    }

    fun setSilentNotifications(enabled: Boolean) {
        updateSetting { it.copy(silentNotifications = enabled) }
    }

    fun setCompressImageAttachments(enabled: Boolean) {
        updateSetting { it.copy(compressImageAttachments = enabled) }
    }

    fun setImageAttachmentMaxLongSide(px: Int) {
        updateSetting { it.copy(imageAttachmentMaxLongSide = px) }
    }

    fun setImageAttachmentWebpQuality(quality: Int) {
        updateSetting { it.copy(imageAttachmentWebpQuality = quality) }
    }

    fun setTerminalFontSize(size: Float) {
        updateSetting { it.copy(terminalFontSize = size) }
    }

    /**
     * #113（D2-26）：设置写串行化——原实现读 settings.value 快照后全量写回，
     * 快速连切多个开关时两次写基于过期快照 → 后写覆盖先写的字段（丢修改）。
     * 修复：单消费者 channel 队列，每次写基于上一次写的结果（写链），
     * 与 DataStore 原子 edit 配合，多字段并发更新不丢。
     */
    private val settingsWriteMutex = Mutex()
    private var pendingSettings: AppSettings? = null

    private fun updateSetting(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsWriteMutex.withLock {
                // 基准 = 上一次写的结果（若 pending 尚未消费）或当前设置
                val base = pendingSettings ?: settings.value
                val updated = transform(base)
                pendingSettings = updated
                updateSettingsUseCase(updated)
            }
        }
    }
}
