package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 管理此前内联在 [ChatViewModel] 中的 12 个 UI 设置 [StateFlow]。
 *
 * 每个设置项将 [SettingsRepository.getSettingsFlow] 映射为细粒度的
 * [StateFlow]，以 [WhileSubscribed5s] 订阅策略在 [scope] 中共享。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的
 * [CoroutineScope]（viewModelScope），Hilt 无法提供。ChatViewModel 直接
 * 构造它并将每个成员作为门面重新暴露，因此 UI 文件无需改动。
 */
internal class SettingsStateDelegate(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val chatFontSize = settingsRepository.getSettingsFlow().map { it.chatFontSize }.stateIn(
        scope, WhileSubscribed5s, "medium"
    )
    val chatDensity = settingsRepository.getSettingsFlow().map { it.chatDensity }.stateIn(
        scope, WhileSubscribed5s, "normal"
    )
    val confirmBeforeSend = settingsRepository.getSettingsFlow().map { it.confirmBeforeSend }.stateIn(
        scope, WhileSubscribed5s, false
    )
    val compactMessages = settingsRepository.getSettingsFlow().map { it.compactMessages }.stateIn(
        scope, WhileSubscribed5s, false
    )
    val autoExpandTools = settingsRepository.getSettingsFlow().map { it.autoExpandTools }.stateIn(
        scope, WhileSubscribed5s, false
    )
    val expandReasoning = settingsRepository.getSettingsFlow().map { it.expandReasoning }.stateIn(
        scope, WhileSubscribed5s, false
    )
    val showTurnDividers = settingsRepository.getSettingsFlow().map { it.showTurnDividers }.stateIn(
        scope, WhileSubscribed5s, true
    )
    val hapticFeedback = settingsRepository.getSettingsFlow().map { it.hapticFeedback }.stateIn(
        scope, WhileSubscribed5s, true
    )
    val showPendingTodoDrawer = settingsRepository.getSettingsFlow().map { it.showPendingTodoDrawer }.stateIn(
        scope, WhileSubscribed5s, true
    )
    val keepScreenOn = settingsRepository.getSettingsFlow().map { it.keepScreenOn }.stateIn(
        scope, WhileSubscribed5s, false
    )
    val compressImageAttachments = settingsRepository.getSettingsFlow().map { it.compressImageAttachments }.stateIn(
        scope, WhileSubscribed5s, true
    )
    val imageAttachmentMaxLongSide = settingsRepository.getSettingsFlow().map { it.imageAttachmentMaxLongSide }.stateIn(
        scope, WhileSubscribed5s, 1440
    )
    val imageAttachmentWebpQuality = settingsRepository.getSettingsFlow().map { it.imageAttachmentWebpQuality }.stateIn(
        scope, WhileSubscribed5s, 60
    )
}
