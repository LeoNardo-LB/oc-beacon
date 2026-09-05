package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.PendingInteractionStore
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingBgNotifier"

/**
 * #336：审批/提问通知退后台补发——前台挂起事件在退后台时刻补通知。
 *
 * #320 为到达时语义：app 前台且用户正看该会话时，PermissionAsked/QuestionAsked
 * 的系统通知被抑制（转会话内提示音）。若用户始终不应答直接退后台，该等待态
 * 从此无通知可见。本组件补上这条沿：
 *
 * - 触发源 = [SessionFocusHolder.isAppInForeground] 的 true→false 转换沿
 *  （OpenCodeApp 的 ProcessLifecycleOwner 观测写入；初始发射与 bg→fg 沿不触发）；
 * - 转换时刻扫描 [PendingInteractionStore]：存在挂起且未通知的会话 → 经
 *   [NotificationActionPort] 补发对应 kind 通知（审批=permission / 提问族=
 *   question；子会话冒泡到父目标，镜像 SessionNotificationCoordinator 发布口径）；
 * - 去重 = store 已通知槽（「已发过的不重发」）：到达路径发布后同点标记
 *  （SessionNotificationCoordinator），本组件补发后也标记；槽随 pending 清除
 *  /kind 切换复位，下一轮可再补发。补发载荷为空串——AppNotificationManager
 *   侧回退最近用户消息/泛化文案；通知 id 走 (server, session, kind) 稳定槽位，
 *   与到达通知同 id 原位更新不堆叠（#320 契约）。
 *
 * 订阅姿势与保活同 [PendingInteractionNotificationRevoker]（appScope init 订阅、
 * OpenCodeConnectionService 构造注入保活——连接服务运行期生效）。撤通知仍归
 * revoker：pending 清除径同点撤对应 kind 通知，补发的通知同样被撤（不回归）。
 */
@Singleton
class PendingInteractionBackgroundNotifier @Inject constructor(
    private val store: PendingInteractionStore,
    private val sessionFocusHolder: SessionFocusHolder,
    private val actions: NotificationActionPort,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository,
    private val serverConfigRepository: ServerConfigRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** sessionId → 曾归属的 serverId 集合（只增不减——与 revoker 快照同姿势）。 */
    private val knownServersBySession = ConcurrentHashMap<String, Set<String>>()

    init {
        // 归属快照：serverSessions 每次发射合并进快照
        appScope.launch {
            eventDispatcher.serverSessions.collect { byServer ->
                byServer.forEach { (serverId, sessionIds) ->
                    sessionIds.forEach { sessionId ->
                        knownServersBySession.merge(sessionId, setOf(serverId)) { a, b -> a + b }
                    }
                }
            }
        }
        // 前台→后台转换沿 → 扫描补发
        appScope.launch {
            var prev: Boolean? = null
            sessionFocusHolder.isAppInForeground.collect { foreground ->
                if (prev == true && !foreground) {
                    dispatchPendingNotifications()
                }
                prev = foreground
            }
        }
    }

    private suspend fun dispatchPendingNotifications() {
        // 门控与到达路径 maybeNotify 同门：通知总开关关闭时不补发（槽保持未标记）
        if (!settingsRepository.notificationsEnabled().first()) return

        store.pendingBySession.value.forEach { (sessionId, kind) ->
            // 已发过的不重发（到达发布点已标记 / 本组件补发后已标记）
            if (store.isNotified(sessionId)) return@forEach
            val servers = knownServersBySession[sessionId].orEmpty()
                .mapNotNull { serverId -> serverConfigRepository.getServer(serverId) }
            if (servers.isEmpty()) {
                AppLogger.w(TAG, "Skip background re-dispatch for $sessionId: no resolvable server ownership")
                return@forEach
            }
            // 子会话冒泡到父目标（镜像 SessionNotificationCoordinator 发布口径）
            val targetSessionId = bubbleToParentSession(sessionId)
            servers.forEach { server ->
                when (SessionNotificationKind.forPendingInteraction(kind)) {
                    SessionNotificationKind.PERMISSION ->
                        actions.showPermissionAsked(server, targetSessionId, "")
                    SessionNotificationKind.QUESTION ->
                        actions.showQuestionAsked(server, targetSessionId, "")
                    else -> return@forEach
                }
                AppLogger.i(
                    TAG,
                    "Background re-dispatch ${kind.name} notification for session $sessionId " +
                        "(target=$targetSessionId, server=${server.id})",
                )
            }
            // 补发后同点标记（键=store 键——原始事件 sessionId；同 sessionId 多服务器
            // 归属为 #311 单键域既有取舍，一次标记覆盖）
            store.markNotified(sessionId, kind)
        }
    }

    /** 子智能体会话冒泡到父会话通知目标；非子会话/查无父时原样返回。 */
    private fun bubbleToParentSession(sessionId: String): String =
        eventDispatcher.sessions.value.firstOrNull { it.id == sessionId }?.parentId ?: sessionId
}

