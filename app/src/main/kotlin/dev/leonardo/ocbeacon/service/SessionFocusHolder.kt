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
     * 当此会话的通知应被抑制时返回 true（应用在前台 且 用户正在查看此确切会话）。
     *
     * 双用途（#175：原 shouldSuppress/shouldSuppressEvent 两方法在 2026-08-16
     * 对齐前台条件后方法体逐字相同，合并为一）：
     * - TaskComplete 通知：用户正在看该会话时完成通知是噪音；
     * - 权限/问题/错误事件通知：正在交互中弹出通知只会打断当前操作。
     *
     * 2026-08-16 修复史（通知 P1）：必须含 isAppInForeground 条件——聊天页按
     * Home 回桌面后 focus 未清（DisposableEffect 不触发），若无论前后台都抑制，
     * 该会话的权限/问题/错误通知在后台被静默吞掉。回桌面 = 看不到界面 = 不该抑制。
     *
     * #137（N-01）已知微竞态（保持现状）：_activeFocus 与 _isAppInForeground
     * 两次独立读非合并快照——两读之间状态变化会导致判断基于混合时刻；
     * 影响窗口为纳秒级且结果仅差一次通知，不构成实际问题。
     */
    fun shouldSuppress(serverId: String, sessionId: String): Boolean {
        val foreground = _isAppInForeground.value
        val focus = _activeFocus.value ?: return false
        return foreground &&
                focus.serverId == serverId &&
                focus.sessionId == sessionId
    }
}
