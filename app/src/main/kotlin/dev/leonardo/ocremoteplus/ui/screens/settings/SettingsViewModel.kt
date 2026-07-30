package dev.leonardo.ocremoteplus.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremoteplus.data.repository.PermissionAutoApprover
import dev.leonardo.ocremoteplus.domain.model.AppSettings
import dev.leonardo.ocremoteplus.domain.model.AutoApproveRule
import dev.leonardo.ocremoteplus.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocremoteplus.domain.usecase.UpdateSettingsUseCase
import dev.leonardo.ocremoteplus.ui.WhileSubscribed5s
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // --- Convenience properties (mapped from aggregated settings flow) ---

    val appLanguage = settings.map { it.appLanguage }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val appTheme = settings.map { it.appTheme }.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val dynamicColor = settings.map { it.dynamicColor }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val chatFontSize = settings.map { it.chatFontSize }.stateIn(viewModelScope, SharingStarted.Eagerly, "medium")
    val chatDensity = settings.map { it.chatDensity }.stateIn(viewModelScope, SharingStarted.Eagerly, "normal")
    val notificationsEnabled = settings.map { it.notificationsEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val initialMessageCount = settings.map { it.initialMessageCount }.stateIn(viewModelScope, SharingStarted.Eagerly, 50)
    val recentDirectoryCount = settings.map { it.recentDirectoryCount }.stateIn(viewModelScope, SharingStarted.Eagerly, 20)
    val codeWordWrap = settings.map { it.codeWordWrap }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
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
    val showLocalRuntime = settings.map { it.showLocalRuntime }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val silentNotifications = settings.map { it.silentNotifications }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val terminalFontSize = settings.map { it.terminalFontSize }.stateIn(viewModelScope, SharingStarted.Eagerly, 13f)
    val localProxyEnabled = settings.map { it.localProxyEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val localProxyUrl = settings.map { it.localProxyUrl }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val localProxyNoProxy = settings.map { it.localProxyNoProxy }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val localServerAllowLan = settings.map { it.localServerAllowLan }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val localServerUsername = settings.map { it.localServerUsername }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val localServerPassword = settings.map { it.localServerPassword }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val localServerRunInBackground = settings.map { it.localServerRunInBackground }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val localServerAutoStart = settings.map { it.localServerAutoStart }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val localServerStartupTimeoutSec = settings.map { it.localServerStartupTimeoutSec }.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    // --- Permission auto-approve rules ---
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

    fun setCodeWordWrap(enabled: Boolean) {
        updateSetting { it.copy(codeWordWrap = enabled) }
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

    fun setShowLocalRuntime(enabled: Boolean) {
        updateSetting { it.copy(showLocalRuntime = enabled) }
    }

    fun setTerminalFontSize(size: Float) {
        updateSetting { it.copy(terminalFontSize = size) }
    }

    fun setLocalProxyEnabled(enabled: Boolean) {
        updateSetting { it.copy(localProxyEnabled = enabled) }
    }

    fun setLocalProxyUrl(url: String) {
        updateSetting { it.copy(localProxyUrl = url) }
    }

    fun setLocalProxyNoProxy(value: String) {
        updateSetting { it.copy(localProxyNoProxy = value) }
    }

    fun setLocalServerAllowLan(enabled: Boolean) {
        updateSetting { it.copy(localServerAllowLan = enabled) }
    }

    fun setLocalServerUsername(value: String) {
        updateSetting { it.copy(localServerUsername = value) }
    }

    fun setLocalServerPassword(value: String) {
        updateSetting { it.copy(localServerPassword = value) }
    }

    fun setLocalServerRunInBackground(enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.value
            val updated = current.copy(localServerRunInBackground = enabled)
            if (!enabled) {
                updateSettingsUseCase(updated.copy(localServerAutoStart = false))
            } else {
                updateSettingsUseCase(updated)
            }
        }
    }

    fun setLocalServerAutoStart(enabled: Boolean) {
        updateSetting { it.copy(localServerAutoStart = enabled) }
    }

    fun setLocalServerStartupTimeoutSec(value: Int) {
        updateSetting { it.copy(localServerStartupTimeoutSec = value) }
    }

    private fun updateSetting(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val current = settings.value
            updateSettingsUseCase(transform(current))
        }
    }
}
