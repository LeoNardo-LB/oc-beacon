package dev.leonardo.ocbeacon.service

/**
 * 协调会话作用域的事件通知：用户当前正在查看的会话会抑制自身的通知。
 *
 * 生命周期（由 ChatScreen 进入/离开驱动）：
 *  - 进入会话的 ChatScreen 时调用 [activate]。
 *  - 离开时调用 [deactivate]。
 *  - 事件通知通过 [postUnlessActive] 路由，仅当 ([serverId], [sessionId])
 *    不是当前活跃会话时才执行 [post]。
 *
 * 仅持有内存状态——平台无关且可单元测试。进入会话时清除已发布通知
 * 由 [AppNotificationManager.cancelSessionNotifications] 单独处理；此协调器
 * 是防止会话处于焦点时发布新通知的门控。
 */
object SessionNotificationCoordinator {

    /** 当前处于焦点的 (serverId, sessionId)，无活跃会话时为 null。 */
    private var activeSession: Pair<String, String>? = null

    /** 将 ([serverId], [sessionId]) 标记为当前处于焦点的会话。 */
    @Synchronized
    fun activate(serverId: String, sessionId: String) {
        activeSession = serverId to sessionId
    }

    /** 清除活跃会话。无活跃会话时调用是安全的。 */
    @Synchronized
    fun deactivate() {
        activeSession = null
    }

    /**
     * 除非 ([serverId], [sessionId]) 是活跃会话，否则执行 [post]。
     *
     * @return 若执行了 [post] 返回 `true`；若因会话当前处于焦点而被抑制则返回 `false`。
     */
    @Synchronized
    fun postUnlessActive(serverId: String, sessionId: String, post: () -> Unit): Boolean {
        if (activeSession == serverId to sessionId) return false
        post()
        return true
    }
}
