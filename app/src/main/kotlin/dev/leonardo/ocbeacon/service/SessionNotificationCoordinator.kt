package dev.leonardo.ocbeacon.service

/**
 * Coordinates session-scoped event notifications: the session the user is
 * currently viewing suppresses its own notifications.
 *
 * Lifecycle (driven by ChatScreen enter/leave):
 *  - [activate] when entering a session's ChatScreen.
 *  - [deactivate] when leaving.
 *  - Event notifications route through [postUnlessActive], which runs [post]
 *    only when ([serverId], [sessionId]) is NOT the active session.
 *
 * Holds in-memory state only — platform-agnostic and unit-testable. Clearing a
 * session's already-posted notifications on entry is handled separately by
 * [AppNotificationManager.cancelSessionNotifications]; this coordinator is the
 * gate that prevents new ones from being posted while the session is in focus.
 */
object SessionNotificationCoordinator {

    /** The (serverId, sessionId) currently in focus, or null when none is active. */
    private var activeSession: Pair<String, String>? = null

    /** Mark ([serverId], [sessionId]) as the session currently in focus. */
    @Synchronized
    fun activate(serverId: String, sessionId: String) {
        activeSession = serverId to sessionId
    }

    /** Clear the active session. Safe to call when nothing is active. */
    @Synchronized
    fun deactivate() {
        activeSession = null
    }

    /**
     * Run [post] unless ([serverId], [sessionId]) is the active session.
     *
     * @return `true` if [post] ran, `false` if it was suppressed because the
     *  session is currently in focus.
     */
    @Synchronized
    fun postUnlessActive(serverId: String, sessionId: String, post: () -> Unit): Boolean {
        if (activeSession == serverId to sessionId) return false
        post()
        return true
    }
}
