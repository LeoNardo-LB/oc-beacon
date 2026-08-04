package dev.leonardo.ocbeacon.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SessionFocus(
    val serverId: String,
    val sessionId: String
)

/**
 * 跟踪应用的前台状态和当前正在查看的会话。
 * 由 [OpenCodeConnectionService] 使用，用于在用户正在主动查看该会话时
 * 抑制 TaskComplete 通知。
 */
@Singleton
class SessionFocusHolder @Inject constructor() {

    val activeFocus: StateFlow<SessionFocus?> get() = _activeFocus
    private val _activeFocus = MutableStateFlow<SessionFocus?>(null)

    val isAppInForeground: StateFlow<Boolean> get() = _isAppInForeground
    private val _isAppInForeground = MutableStateFlow(false)

    fun setActiveFocus(serverId: String?, sessionId: String?) {
        _activeFocus.value = if (serverId != null && sessionId != null) {
            SessionFocus(serverId, sessionId)
        } else {
            null
        }
    }

    fun setAppInForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    /**
     * 当应抑制此会话的 TaskComplete 通知时返回 true
     *（应用在前台 且 用户正在查看此确切会话）。
     */
    fun shouldSuppress(serverId: String, sessionId: String): Boolean {
        val focus = _activeFocus.value ?: return false
        return _isAppInForeground.value &&
                focus.serverId == serverId &&
                focus.sessionId == sessionId
    }
}
