package dev.leonardo.octether.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SessionFocus(
    val serverId: String,
    val sessionId: String
)

/**
 * Tracks the app's foreground state and the currently-viewed session.
 * Used by [OpenCodeConnectionService] to suppress TaskComplete notifications
 * when the user is actively viewing that session.
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
     * Returns true if TaskComplete notifications for this session should be suppressed
     * (app is in foreground AND user is viewing this exact session).
     */
    fun shouldSuppress(serverId: String, sessionId: String): Boolean {
        val focus = _activeFocus.value ?: return false
        return _isAppInForeground.value &&
                focus.serverId == serverId &&
                focus.sessionId == sessionId
    }
}
