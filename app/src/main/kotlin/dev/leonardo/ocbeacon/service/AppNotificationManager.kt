package dev.leonardo.ocbeacon.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.MainActivity
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 用于通知显示的用户消息预览。 */
data class UserMessagePreview(
    val text: String,
    val timestamp: Long
)

private const val NOTIFICATION_CHANNEL_ID = "opencode_connection"
private const val NOTIFICATION_CHANNEL_TASKS_ID = "opencode_tasks"
private const val NOTIFICATION_CHANNEL_TASKS_SILENT_ID = "opencode_tasks_silent"
private const val NOTIFICATION_CHANNEL_PERMISSIONS_ID = "opencode_permissions"
private const val NOTIFICATION_CHANNEL_QUESTIONS_ID = "opencode_questions"

/**
 * 管理连接服务的所有通知逻辑。
 * 从 [OpenCodeConnectionService] 中抽取出来以实现关注点分离。
 */
@Singleton
class AppNotificationManager @Inject constructor(
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsDataStore,
    private val sessionFocusHolder: SessionFocusHolder,
    private val feedbackPlayer: InSessionFeedbackPlayer,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val TAG = "AppNotificationMgr"

    /**
     * 会话按 id 的索引缓存（N13 优化）。
     * 由 [EventDispatcher.sessions] flow 驱动，避免每次通知都线性扫描
     * 全部会话。sessions 是 List，但通知路径（isChildSession /
     * getSessionInfo / buildSessionPath）只需按 id 查找。
     */
    @Volatile
    private var sessionById: Map<String, Session> = emptyMap()

    init {
        appScope.launch {
            eventDispatcher.sessions.collect { sessions ->
                sessionById = sessions.associateBy { it.id }
            }
        }
    }

    /** 按 (服务器, 会话) 对每个会话的响应就绪通知去重。 */
    private val lastNotifiedAssistantMessageBySession = ConcurrentHashMap<String, String>()

    /** 按 (服务器, 会话) 对每个会话的权限通知去重。 */
    private val lastNotifiedPermissionBySession = ConcurrentHashMap<String, String>()

    /** 按 (服务器, 会话) 对每个会话的问题通知去重。 */
    private val lastNotifiedQuestionBySession = ConcurrentHashMap<String, String>()

    // ============ 通知渠道 ============

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

            val questionsChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_QUESTIONS_ID,
                context.getString(R.string.notification_channel_questions),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_questions_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(connectionChannel)
            notificationManager.createNotificationChannel(tasksChannel)
            notificationManager.createNotificationChannel(tasksSilentChannel)
            notificationManager.createNotificationChannel(permissionsChannel)
            notificationManager.createNotificationChannel(questionsChannel)
        }
    }

    // ============ 持久通知（InboxStyle，多服务器）============

    fun createPersistentNotification(
        context: Context,
        connections: Map<String, ServerConnectionState>
    ): Notification {
        val visibleConnections = connections.values
        val serverCount = visibleConnections.size

        // 点击通知：有已连接/连接中的服务器时进入该服务器的会话列表；
        // 无连接时打开主页。通过 ACTION_OPEN_SESSION + 服务器参数复用
        // MainActivity 的深链处理（无 sessionId → NavGraph 导航到会话列表）。
        val tapIntent = if (visibleConnections.isNotEmpty()) {
            val server = visibleConnections.first().config
            Intent(context, MainActivity::class.java).apply {
                action = OpenCodeConnectionService.ACTION_OPEN_SESSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(OpenCodeConnectionService.EXTRA_SERVER_ID, server.id)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Disconnect All 操作
        val disconnectAllIntent = Intent(context, OpenCodeConnectionService::class.java).apply {
            action = OpenCodeConnectionService.ACTION_DISCONNECT_ALL
        }
        val disconnectAllPendingIntent = PendingIntent.getService(
            context, 1, disconnectAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            .setSmallIcon(R.drawable.ic_notification)
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

        // 多服务器时使用 InboxStyle
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

    /**
     * 通知自检（验收①2026-08-21 根因收尾）：在任务渠道（IMPORTANCE_HIGH）投递一条真实测试通知。
     *
     * 背景：设备厂商（MIUI/HyperOS 等）对旁装载应用的渠道默认关闭「悬浮通知/声音/振动」，
     * 且该策略在标准 [NotificationChannel] API 中不可见——渠道的 importance/sound/vibration
     * 在被静默时仍报告正常（本机实证：渠道 importance=4、mSound 有值，但横幅不弹）。
     * 因此唯一可靠的自检是端到端投递 + 用户感知确认；未看到横幅时引导用户去
     * 系统通知设置打开对应渠道的悬浮通知。
     */
    fun sendSelfTestNotification(context: Context, notificationManager: NotificationManager) {
        // 幂等建渠道：连接服务未启动（从未添加过服务器）时渠道可能尚不存在
        createNotificationChannels(notificationManager, context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_test_title))
            .setContentText(context.getString(R.string.notification_test_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // 固定 id：重复自检互相替换，不堆积
        notificationManager.notify(stableHash("selftest"), notification)
        AppLogger.i(TAG, "Self-test notification posted on channel " + NOTIFICATION_CHANNEL_TASKS_ID)
    }

    fun updatePersistentNotification(
        context: Context,
        notificationManager: NotificationManager,
        connections: Map<String, ServerConnectionState>
    ) {
        val notification = createPersistentNotification(context, connections)
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }

    // ============ 事件通知（按服务器分组）============

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

        // 以最新的用户消息作为内容文本（单行，截断处理）
        val userMessages = findLatestUserMessages(sessionId, 1)
        val contentText = userMessages.firstOrNull()?.text
            ?: context.getString(R.string.notification_new_message)

        val pendingIntent = createSessionPendingIntent(context, server, sessionId, sessionId.hashCode())
        val silent = settingsRepository.silentNotifications.first()
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID
        val notifId = eventNotificationId(server.id, sessionId, 0)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
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

        if (sessionFocusHolder.shouldSuppress(server.id, sessionId)) return
        notificationManager.notify(notifId, builder.build())
        showServerGroupSummary(context, notificationManager, server)
    }

    fun showPermissionNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String,
        permission: String
    ) {
        // 去重 + 抑制：key 含 serverId，避免跨服务器同 sessionId 误判
        if (!shouldNotifyPermission(server.id, sessionId, permission)) return

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        markPermissionNotified(server.id, sessionId, permission)
        notificationManager.notify(notifId, notification)
        showServerGroupSummary(context, notificationManager, server)
    }

    fun showQuestionNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String,
        questionText: String
    ) {
        // 去重 + 抑制：key 含 serverId，避免跨服务器同 sessionId 误判
        if (!shouldNotifyQuestion(server.id, sessionId, questionText)) return
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val displayName = sessionTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_new_session)
        val title = "${context.getString(R.string.notification_tag_question)} · $displayName"
        // P3（2026-08-19）：正文优先问题文本本身——短且直接（"What is your
        // favorite animal?"）；此前优先最后一条用户消息，正文是触发 prompt
        // 全文（"Use the question tool to ask me: ..."）信息密度低。问题文本
        // 缺失时回退用户消息（REST 兜底路径可能无 question 文本）。
        val contentText = questionText.ifBlank {
            findLatestUserMessages(sessionId, 1).firstOrNull()?.text
                ?: context.getString(R.string.notification_new_message)
        }

        val notifId = eventNotificationId(server.id, sessionId, 2000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_QUESTIONS_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        markQuestionNotified(server.id, sessionId, questionText)
        notificationManager.notify(notifId, notification)
        showServerGroupSummary(context, notificationManager, server)
    }

    /**
     * 从 REST 轮询结果触发问题通知（SSE 兜底）。
     * 对每个会话：仅通知新增问题（diff）；去重/抑制复用既有逻辑。
     */
    fun notifyPendingQuestionsFromREST(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        questionsBySession: Map<String, List<SseEvent.QuestionAsked>>,
        previousKnown: Map<String, Set<String>>
    ) {
        val newQuestions = diffNewQuestionIds(previousKnown, questionsBySession)
        newQuestions.forEach { (sessionId, questions) ->
            val targetSessionId = if (isChildSession(sessionId)) {
                sessionById[sessionId]?.parentId ?: sessionId
            } else sessionId
            if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) {
                // #155：REST 兜底路径的被抑制问题 → 会话内提示音（独立去重防 SSE/REST 双响）
                appScope.launch {
                    val enabled = settingsRepository.notificationsEnabled.first()
                    if (!enabled) return@launch
                    val silent = settingsRepository.silentNotifications.first()
                    questions.forEach { question ->
                        val text = question.questions.firstOrNull()?.question
                            ?: question.questions.firstOrNull()?.header
                            ?: context.getString(
                                R.string.notification_has_question,
                                context.getString(R.string.notification_new_session)
                            )
                        feedbackPlayer.playIfFocused(
                            serverId = server.id,
                            sessionId = targetSessionId,
                            type = FeedbackType.QUESTION,
                            dedupKey = text,
                            silentNotifications = silent,
                            notificationsEnabled = enabled,
                        )
                    }
                }
                return@forEach
            }
            questions.forEach { question ->
                // 与 SSE 路径对齐：文本缺失时回退到本地化字符串，
                // 同时避免空字符串削弱 shouldNotifyQuestion 的去重键。
                val text = question.questions.firstOrNull()?.question
                    ?: question.questions.firstOrNull()?.header
                    ?: context.getString(
                        R.string.notification_has_question,
                        context.getString(R.string.notification_new_session)
                    )
                showQuestionNotification(context, notificationManager, server, targetSessionId, text)
            }
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
        // 错误内容：JSON/数组错误包不可读但不应完全丢弃，保留前 200 字符
        val safeError = error.trim().let { raw ->
            if (raw.startsWith("{") || raw.startsWith("[")) raw.take(200)
            else raw
        }
        val contentText = (sessionId.let { findLatestUserMessages(it, 1).firstOrNull()?.text })
            ?: safeError.ifBlank { context.getString(R.string.notification_new_message) }

        val notifId = eventNotificationId(server.id, sessionId, 3000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup("server_${server.id}")
            .build()

        if (sessionFocusHolder.shouldSuppress(server.id, sessionId)) return
        notificationManager.notify(notifId, notification)
        showServerGroupSummary(context, notificationManager, server)
    }

    // ============ 通知去重 / 会话辅助方法 ============

    /**
     * 检查会话是否为子/子代理会话（已设置 parentID）。
     * 子会话不应触发面向用户的通知。
     */
    fun isChildSession(sessionId: String): Boolean {
        val session = sessionById[sessionId]
        return session?.parentId != null
    }

    /**
     * 纯查询：会话是否有带文本输出的最新 assistant 消息（不读写去重状态）。
     * #155 拆分：提示音路径只读（不污染通知去重 map，Q11），通知路径
     * 由 [checkNewAssistantMessage] 组合查询+去重写入。
     */
    fun computeNewAssistantMessageId(sessionId: String): String? {
        val sessionMessages = eventDispatcher.messages.value[sessionId] ?: return null
        val latestAssistant = sessionMessages
            .asReversed()
            .firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return null

        // 错误消息始终视为有内容
        if (!latestAssistant.error?.message.isNullOrBlank()) return latestAssistant.id

        // 检查是否有文本输出
        val parts = eventDispatcher.parts.value[latestAssistant.id] ?: return null
        val hasTextOutput = parts.any { part ->
            when (part) {
                is Part.Text -> part.text.isNotBlank()
                is Part.Reasoning -> part.text.isNotBlank()
                else -> false
            }
        }
        if (!hasTextOutput) return null
        return latestAssistant.id
    }

    /** 通知路径的去重写入（与 [computeNewAssistantMessageId] 配对）。 */
    fun markAssistantNotified(serverId: String, sessionId: String, messageId: String) {
        lastNotifiedAssistantMessageBySession[sessionNotificationKey(serverId, sessionId)] = messageId
    }

    /**
     * 检查会话是否有新的可通知 assistant 消息（查询+去重一体，通知路径用）。
     * 若该消息应触发通知则返回其消息 ID，否则返回 null。
     */
    fun checkNewAssistantMessage(serverId: String, sessionId: String): String? {
        val messageId = computeNewAssistantMessageId(sessionId) ?: return null
        // 去重
        val notifKey = sessionNotificationKey(serverId, sessionId)
        if (lastNotifiedAssistantMessageBySession[notifKey] == messageId) return null
        markAssistantNotified(serverId, sessionId, messageId)
        return messageId
    }

    /**
     * 提取最新的 N 条用户消息（非合成）用于 MessagingStyle 显示。
     * 消息按从旧到新排序。
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
     * 取消指定会话的所有事件通知（TaskComplete/Permission/Question/Error）。
     * 在用户进入该会话的 ChatScreen 时调用。
     * 不会取消服务器分组摘要（其他会话可能仍有通知）。
     */
    fun cancelSessionNotifications(
        notificationManager: NotificationManager,
        serverId: String,
        sessionId: String
    ) {
        for (offset in intArrayOf(0, 1000, 2000, 3000)) {
            notificationManager.cancel(eventNotificationId(serverId, sessionId, offset))
        }
        // 重置去重状态，以便下一轮 permission/question/assistant 消息能再次通知
        val notifKey = sessionNotificationKey(serverId, sessionId)
        lastNotifiedPermissionBySession.remove(notifKey)
        lastNotifiedQuestionBySession.remove(notifKey)
        lastNotifiedAssistantMessageBySession.remove(notifKey)
        // #155：进入会话 → 错误 streak 随去重一并重置（spec §5.3）
        feedbackPlayer.onSessionEntered(serverId, sessionId)
    }

    /**
     * 清除指定服务器全部会话的去重缓存（防服务器级残留增长）。
     * 在服务器断开连接时调用。
     */
    fun clearForServer(serverId: String) {
        lastNotifiedPermissionBySession.keys.removeIf { it.startsWith("$serverId::") }
        lastNotifiedQuestionBySession.keys.removeIf { it.startsWith("$serverId::") }
        lastNotifiedAssistantMessageBySession.keys.removeIf { it.startsWith("$serverId::") }
    }

    /**
     * 清除单会话的去重缓存（内存泄漏修复 #89，会话退出时由 EventDispatcher 调用）。
     * 旧代码仅 clearForServer（断开）与 dismissSessionNotifications（用户操作）时清理，
     * 正常切换会话 3 条 key 永驻 → 按 (server, session) 无限增长。
     */
    fun clearForSession(serverId: String, sessionId: String) {
        val prefix = sessionNotificationKey(serverId, sessionId)
        lastNotifiedPermissionBySession.remove(prefix)
        lastNotifiedQuestionBySession.remove(prefix)
        lastNotifiedAssistantMessageBySession.remove(prefix)
    }

    // ============ 私有辅助方法 ============

    private fun showServerGroupSummary(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig
    ) {
        // 使用独立命名空间（"summary" 前缀），避免与事件通知 ID 空间碰撞
        val summaryId = stableHash("summary", server.id)
        val summary = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_SILENT_ID)
            .setContentTitle(server.displayName)
            .setContentText(context.getString(R.string.notification_group_summary))
            .setSmallIcon(R.drawable.ic_notification)
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
        val session = sessionById[sessionId]
        if (session == null) {
            AppLogger.w(TAG, "buildSessionPath: session $sessionId not found")
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
        val session = sessionById[sessionId]
        return Pair(session?.title, session?.directory)
    }

    /** 通知去重的 (服务器, 会话) 组合键——sessionId 是服务器内部 ID，跨服务器可能重复。 */
    private fun sessionNotificationKey(serverId: String, sessionId: String): String = "$serverId::$sessionId"

    // ============ 去重 / 抑制纯函数（可单测，不依赖 Android framework） ============

    /**
     * 权限通知是否应通知：未通知过相同权限 且 会话未被抑制。
     * 纯查询，不修改状态——真正通知后由 [markPermissionNotified] 记录。
     */
    internal fun shouldNotifyPermission(serverId: String, sessionId: String, permission: String): Boolean {
        if (sessionFocusHolder.shouldSuppress(serverId, sessionId)) return false
        return lastNotifiedPermissionBySession[sessionNotificationKey(serverId, sessionId)] != permission
    }

    /** 记录权限已通知（去重状态写入）。 */
    internal fun markPermissionNotified(serverId: String, sessionId: String, permission: String) {
        lastNotifiedPermissionBySession[sessionNotificationKey(serverId, sessionId)] = permission
    }

    /**
     * 问题通知是否应通知：未通知过相同问题 且 会话未被抑制。
     * 纯查询，不修改状态——真正通知后由 [markQuestionNotified] 记录。
     */
    internal fun shouldNotifyQuestion(serverId: String, sessionId: String, questionText: String): Boolean {
        if (sessionFocusHolder.shouldSuppress(serverId, sessionId)) return false
        return lastNotifiedQuestionBySession[sessionNotificationKey(serverId, sessionId)] != questionText
    }

    /** 记录问题已通知（去重状态写入）。 */
    internal fun markQuestionNotified(serverId: String, sessionId: String, questionText: String) {
        lastNotifiedQuestionBySession[sessionNotificationKey(serverId, sessionId)] = questionText
    }

    private fun eventNotificationId(serverId: String, sessionId: String, typeOffset: Int): Int {
        return stableHash(serverId, sessionId) + typeOffset
    }

    /**
     * FNV-1a 32 位稳定 hash。
     * 相比字符串拼接 + hashCode()：无拼接歧义（"a"+"bc" 与 "ab"+"c" 不再同值），
     * 且跨 JVM/平台行为一致，语义明确。
     */
    private fun stableHash(vararg parts: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (part in parts) {
            for (i in part.indices) {
                hash = (hash xor part[i].code) * 0x01000193
            }
        }
        return hash
    }

    companion object {
        const val PERSISTENT_NOTIFICATION_ID = 1001
    }
}

/**
 * 对比上次已知问题 id 与当前问题列表，返回每个会话的新增问题（按 id 判断）。
 * REST 轮询兜底使用——SSE 不推 question 事件时也能发现新提问。
 */
internal fun diffNewQuestionIds(
    previous: Map<String, Set<String>>,
    current: Map<String, List<SseEvent.QuestionAsked>>
): Map<String, List<SseEvent.QuestionAsked>> {
    return current.mapNotNull { (sessionId, questions) ->
        val known = previous[sessionId].orEmpty()
        val newOnes = questions.filter { it.id !in known }
        if (newOnes.isEmpty()) null else sessionId to newOnes
    }.toMap()
}
