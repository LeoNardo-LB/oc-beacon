package dev.leonardo.octether.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.MainActivity
import dev.leonardo.octether.R
import dev.leonardo.octether.data.repository.EventDispatcher
import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.data.repository.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A user message preview for notification display. */
data class UserMessagePreview(
    val text: String,
    val timestamp: Long
)

private const val NOTIFICATION_CHANNEL_ID = "opencode_connection"
private const val NOTIFICATION_CHANNEL_TASKS_ID = "opencode_tasks"
private const val NOTIFICATION_CHANNEL_TASKS_SILENT_ID = "opencode_tasks_silent"
private const val NOTIFICATION_CHANNEL_PERMISSIONS_ID = "opencode_permissions"

/**
 * Manages all notification logic for the connection service.
 * Extracted from [OpenCodeConnectionService] for separation of concerns.
 */
@Singleton
class AppNotificationManager @Inject constructor(
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsDataStore
) {
    private val TAG = "AppNotificationMgr"

    /** Dedup response-ready notifications per session by last assistant message ID. */
    private val lastNotifiedAssistantMessageBySession = ConcurrentHashMap<String, String>()

    /** Dedup permission notifications per session by permission name. */
    private val lastNotifiedPermissionBySession = ConcurrentHashMap<String, String>()

    /** Dedup question notifications per session by question text. */
    private val lastNotifiedQuestionBySession = ConcurrentHashMap<String, String>()

    // ============ Notification Channels ============

    fun createNotificationChannels(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val connectionChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_connection_desc)
                setShowBadge(false)
            }

            val tasksChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_ID,
                context.getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_tasks_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            val tasksSilentChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_SILENT_ID,
                context.getString(R.string.notification_channel_tasks_silent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_tasks_silent_desc)
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            val permissionsChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PERMISSIONS_ID,
                context.getString(R.string.notification_channel_permissions),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_permissions_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(connectionChannel)
            notificationManager.createNotificationChannel(tasksChannel)
            notificationManager.createNotificationChannel(tasksSilentChannel)
            notificationManager.createNotificationChannel(permissionsChannel)
        }
    }

    // ============ Persistent Notification (InboxStyle, multi-server) ============

    fun createPersistentNotification(
        context: Context,
        connections: Map<String, ServerConnectionState>,
        isLocalServer: (ServerConfig) -> Boolean
    ): Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Disconnect All action
        val disconnectAllIntent = Intent(context, OpenCodeConnectionService::class.java).apply {
            action = OpenCodeConnectionService.ACTION_DISCONNECT_ALL
        }
        val disconnectAllPendingIntent = PendingIntent.getService(
            context, 1, disconnectAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val visibleConnections = connections.values.filterNot { isLocalServer(it.config) }
        val serverCount = visibleConnections.size
        val connectedCount = visibleConnections.count { it.isConnected }

        val title = if (serverCount == 0) {
            context.getString(R.string.app_name)
        } else if (serverCount == 1) {
            val server = visibleConnections.first()
            if (server.isConnected) context.getString(R.string.notification_connected, server.config.displayName)
            else context.getString(R.string.notification_connecting, server.config.displayName)
        } else {
            context.getString(R.string.notification_connected_count, connectedCount, serverCount)
        }

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (serverCount > 0) {
            builder.addAction(
                R.mipmap.ic_launcher,
                context.getString(R.string.notification_disconnect_all),
                disconnectAllPendingIntent
            )
        }

        // InboxStyle when multiple servers
        if (serverCount > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(context.getString(R.string.notification_inbox_title, connectedCount, serverCount))
            for (state in visibleConnections) {
                val status = if (state.isConnected) context.getString(R.string.notification_status_connected)
                else context.getString(R.string.notification_status_connecting)
                inboxStyle.addLine("${state.config.displayName}: $status")
            }
            builder.setStyle(inboxStyle)
        }

        return builder.build()
    }

    fun updatePersistentNotification(
        context: Context,
        notificationManager: NotificationManager,
        connections: Map<String, ServerConnectionState>,
        isLocalServer: (ServerConfig) -> Boolean
    ) {
        val notification = createPersistentNotification(context, connections, isLocalServer)
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }

    // ============ Event Notifications (grouped by server) ============

    suspend fun showTaskCompleteNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String
    ) {
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val displayName = sessionTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_new_session)

        val typeLabel = context.getString(R.string.notification_tag_ready)
        val title = "$typeLabel · $displayName"

        // Latest user message as content text (single line, truncated)
        val userMessages = findLatestUserMessages(sessionId, 1)
        val contentText = userMessages.firstOrNull()?.text
            ?: context.getString(R.string.notification_new_message)

        val pendingIntent = createSessionPendingIntent(context, server, sessionId, sessionId.hashCode())
        val silent = settingsRepository.silentNotifications.first()
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID
        val notifId = eventNotificationId(server.id, sessionId, 0)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup("server_${server.id}")

        if (!silent) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 500, 200, 500))
        }

        SessionNotificationCoordinator.postUnlessActive(server.id, sessionId) {
            notificationManager.notify(notifId, builder.build())
            showServerGroupSummary(context, notificationManager, server)
        }
    }

    fun showPermissionNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String,
        permission: String
    ) {
        // Dedup: skip if same permission already notified for this session
        if (lastNotifiedPermissionBySession[sessionId] == permission) return

        val (sessionTitle, _) = getSessionInfo(sessionId)
        val displayName = sessionTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_new_session)
        val title = "${context.getString(R.string.notification_tag_permission)} · $displayName"
        val contentText = findLatestUserMessages(sessionId, 1).firstOrNull()?.text
            ?: permission.ifBlank { context.getString(R.string.notification_new_message) }

        val notifId = eventNotificationId(server.id, sessionId, 1000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        SessionNotificationCoordinator.postUnlessActive(server.id, sessionId) {
            lastNotifiedPermissionBySession[sessionId] = permission
            notificationManager.notify(notifId, notification)
            showServerGroupSummary(context, notificationManager, server)
        }
    }

    fun showQuestionNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String,
        questionText: String
    ) {
        // Dedup: skip if same question already notified for this session
        if (lastNotifiedQuestionBySession[sessionId] == questionText) return
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val displayName = sessionTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_new_session)
        val title = "${context.getString(R.string.notification_tag_question)} · $displayName"
        val contentText = findLatestUserMessages(sessionId, 1).firstOrNull()?.text
            ?: questionText.ifBlank { context.getString(R.string.notification_new_message) }

        val notifId = eventNotificationId(server.id, sessionId, 2000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        SessionNotificationCoordinator.postUnlessActive(server.id, sessionId) {
            lastNotifiedQuestionBySession[sessionId] = questionText
            notificationManager.notify(notifId, notification)
            showServerGroupSummary(context, notificationManager, server)
        }
    }

    fun showErrorNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String?,
        error: String
    ) {
        if (sessionId == null) return
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val displayName = sessionTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_new_session)
        val title = "${context.getString(R.string.notification_tag_error)} · $displayName"
        val safeError = error.trim().let { if (it.startsWith("{") || it.startsWith("[")) "" else it }
        val contentText = (sessionId.let { findLatestUserMessages(it, 1).firstOrNull()?.text })
            ?: safeError.ifBlank { context.getString(R.string.notification_new_message) }

        val notifId = eventNotificationId(server.id, sessionId, 3000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup("server_${server.id}")
            .build()

        SessionNotificationCoordinator.postUnlessActive(server.id, sessionId) {
            notificationManager.notify(notifId, notification)
            showServerGroupSummary(context, notificationManager, server)
        }
    }

    // ============ Notification Dedup / Session Helpers ============

    /**
     * Check if a session is a child/sub-agent session (has parentID set).
     * Child sessions should not trigger user-facing notifications.
     */
    fun isChildSession(sessionId: String): Boolean {
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        return session?.parentId != null
    }

    /**
     * Check if there's a new notifiable assistant message for the session.
     * Returns the message ID if it should trigger a notification, null otherwise.
     * Handles dedup internally via [lastNotifiedAssistantMessageBySession].
     */
    fun checkNewAssistantMessage(sessionId: String): String? {
        val sessionMessages = eventDispatcher.messages.value[sessionId] ?: return null
        val latestAssistant = sessionMessages
            .asReversed()
            .firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return null

        // Always notify on error messages
        if (!latestAssistant.error?.message.isNullOrBlank()) return latestAssistant.id

        // Check for text output
        val parts = eventDispatcher.parts.value[latestAssistant.id] ?: return null
        val hasTextOutput = parts.any { part ->
            when (part) {
                is Part.Text -> part.text.isNotBlank()
                is Part.Reasoning -> part.text.isNotBlank()
                else -> false
            }
        }
        if (!hasTextOutput) return null

        // Dedup
        val previousNotified = lastNotifiedAssistantMessageBySession[sessionId]
        if (previousNotified == latestAssistant.id) return null

        lastNotifiedAssistantMessageBySession[sessionId] = latestAssistant.id
        return latestAssistant.id
    }

    /**
     * Extract the latest N user messages (non-synthetic) for MessagingStyle display.
     * Messages are ordered oldest-to-newest.
     */
    fun findLatestUserMessages(sessionId: String, limit: Int): List<UserMessagePreview> {
        val sessionMessages = eventDispatcher.messages.value[sessionId] ?: return emptyList()
        val partsMap = eventDispatcher.parts.value

        val previews = sessionMessages
            .filterIsInstance<Message.User>()
            .mapNotNull { userMsg ->
                val parts = partsMap[userMsg.id] ?: return@mapNotNull null
                val text = parts
                    .filterIsInstance<Part.Text>()
                    .firstOrNull { it.synthetic != true && it.ignored != true && it.text.isNotBlank() }
                    ?.text
                    ?: return@mapNotNull null
                val cleanText = text.replace("\n", " ").trim()
                UserMessagePreview(
                    text = if (cleanText.length > 100) cleanText.take(100) + "…" else cleanText,
                    timestamp = userMsg.time.created
                )
            }

        return previews.takeLast(limit)
    }

    /**
     * Cancel all event notifications for a specific session (TaskComplete/Permission/Question/Error).
     * Called when the user enters the session's ChatScreen.
     * Does NOT cancel the server group summary (other sessions may still have notifications).
     */
    fun cancelSessionNotifications(
        notificationManager: NotificationManager,
        serverId: String,
        sessionId: String
    ) {
        for (offset in intArrayOf(0, 1000, 2000, 3000)) {
            notificationManager.cancel(eventNotificationId(serverId, sessionId, offset))
        }
        // Reset dedup state so next round of permission/question can notify again
        lastNotifiedPermissionBySession.remove(sessionId)
        lastNotifiedQuestionBySession.remove(sessionId)
    }

    // ============ Private Helpers ============

    private fun showServerGroupSummary(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig
    ) {
        val summaryId = "server_summary_${server.id}".hashCode()
        val summary = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_SILENT_ID)
            .setContentTitle(server.displayName)
            .setContentText(context.getString(R.string.notification_group_summary))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setGroup("server_${server.id}")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(summaryId, summary)
    }

    private fun createSessionPendingIntent(
        context: Context,
        server: ServerConfig,
        sessionId: String?,
        requestCode: Int
    ): PendingIntent {
        val sessionPath = sessionId?.let { buildSessionPath(it) }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = OpenCodeConnectionService.ACTION_OPEN_SESSION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_URL, server.url)
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_USERNAME, server.username)
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_PASSWORD, server.password ?: "")
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_NAME, server.displayName)
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_ID, server.id)
            sessionPath?.let { putExtra(OpenCodeConnectionService.EXTRA_SESSION_PATH, it) }
            sessionId?.let { putExtra(OpenCodeConnectionService.EXTRA_SESSION_ID, it) }
        }

        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildSessionPath(sessionId: String): String? {
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        if (session == null) {
            Log.w(TAG, "buildSessionPath: session $sessionId not found")
            return null
        }
        val encodedDir = base64UrlEncode(session.directory)
        return "/$encodedDir/session/$sessionId"
    }

    private fun base64UrlEncode(value: String): String {
        val encoded = android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return encoded
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }

    private fun getSessionInfo(sessionId: String): Pair<String?, String?> {
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        return Pair(session?.title, session?.directory)
    }

    private fun getProjectName(directory: String?): String? {
        if (directory.isNullOrBlank()) return null
        return dev.leonardo.octether.util.PathUtils.fileName(directory.trimEnd('/', '\\'))
    }

    private fun eventNotificationId(serverId: String, sessionId: String, typeOffset: Int): Int {
        return (serverId + sessionId).hashCode() + typeOffset
    }

    companion object {
        const val PERSISTENT_NOTIFICATION_ID = 1001
    }
}
