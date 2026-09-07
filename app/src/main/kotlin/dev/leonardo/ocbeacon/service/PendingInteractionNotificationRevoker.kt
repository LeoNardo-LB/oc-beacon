package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.PendingInteractionEntry
import dev.leonardo.ocbeacon.data.repository.PendingInteractionKind
import dev.leonardo.ocbeacon.data.repository.PendingInteractionStore
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingNotifRevoker"

/**
 * #320：待交互（#311 PendingInteractionStore）清除 → 同点撤对应系统通知。
 *
 * 订阅姿势与 PendingInteractionStore 订阅 statusFlow 一致（appScope init
 * 订阅、只读消费）。三清除径（#311 契约）全部收敛在 pendingBySession 的
 * map 变化上，本组件 diff 前后快照即可全覆盖：
 * - ① 本地应答成功（clearIfKind）；
 * - ② 轮次结束兜底（statusFlow → Idle 清）；
 * - ③ 会话删除级联（clearForSession）+ clearAll（断连）。
 *
 * 撤通知口径（与发布侧 SessionNotificationCoordinator 互补，不重复发布）：
 * - 记录新增（null → kind）：不动作——发布归 SSE 管线（SessionIdle /
 *   PermissionAsked / QuestionAsked → AppNotificationManager，V1/V2/DSH
 *   事件流统一入口，DSH mapper 已映射三事件）；
 * - 清除/kind 切换（was ≠ now）：撤 was 对应 kind 槽位的通知
 *   （审批 +1000 / 提问 +2000），并重置该槽去重状态（下一轮同类事件可再通知）；
 * - turn 完成（+0）信息性通知发出后不撤；错误（+3000）不动。
 * - #337：撤除目标镜像发布侧冒泡口径——子会话挂起的通知发布在父会话
 *   id 槽（Coordinator/#336 notifier 共享 bubbleToParentSessionTarget），
 *   撤除解析到同一父槽；父归属凭只增快照（SessionDeleted 级联时 sessions
 *   先于 store 清除被删行，实时查表拿不到——与 knownServersBySession 同姿势）。
 *
 * sessionId 归属服务器快照：EventDispatcher.serverSessions 在 SessionDeleted
 * 级联时先于 store 清除被清（handler.handle 在分发点前段）——撤通知时查
 * 当前注册表会拿不到归属，故本组件自持观察快照（只增不减；多记归属的
 * cancel 为幂等 no-op 无害）。
 *
 * 实例化由 OpenCodeConnectionService 构造注入保活（连接服务运行期生效）。
 */
@Singleton
class PendingInteractionNotificationRevoker @Inject constructor(
    private val store: PendingInteractionStore,
    private val appNotificationManager: AppNotificationManager,
    private val eventDispatcher: EventDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** sessionId → 曾归属的 serverId 集合（只增不减——删除级联后仍可定位通知 id）。 */
    private val knownServersBySession = ConcurrentHashMap<String, Set<String>>()

    /**
     * 子会话行快照（id → Session，仅 parentID 非空者；只增不减）——#337 撤除时
     * 解析冒泡父槽。SessionDeleted 级联时 eventDispatcher.sessions 先于 store
     * 清除被删行，实时查表拿不到父归属——与 knownServersBySession 同姿势自持快照
     * （多记行 cancel 幂等 no-op 无害）。
     */
    private val knownChildSessions = ConcurrentHashMap<String, Session>()

    init {
        // 归属快照：serverSessions 每次发射合并进快照（不清——见类注释）
        appScope.launch {
            eventDispatcher.serverSessions.collect { byServer ->
                byServer.forEach { (serverId, sessionIds) ->
                    sessionIds.forEach { sessionId ->
                        knownServersBySession.merge(sessionId, setOf(serverId)) { a, b -> a + b }
                    }
                }
            }
        }
        // 子会话快照：sessions 每次发射合并子会话行（不清——见字段注释）
        appScope.launch {
            eventDispatcher.sessions.collect { list ->
                list.forEach { session ->
                    if (session.parentId != null) knownChildSessions[session.id] = session
                }
            }
        }
        // 撤通知 diff：清除/kind 切换 → 撤旧 kind 槽位（#344：条目含载荷文本——
        // diff 只比 kind，同 kind 载荷更新不触发撤除）
        appScope.launch {
            var prev: Map<String, PendingInteractionEntry> = emptyMap()
            store.pendingBySession.collect { curr ->
                (prev.keys + curr.keys).forEach sid@{ sessionId ->
                    val was = prev[sessionId] ?: return@sid // 新增记录：发布归 SSE 管线
                    val now = curr[sessionId]
                    if (was.kind != now?.kind) {
                        revoke(sessionId, was.kind)
                    }
                }
                prev = curr
            }
        }
    }

    private fun revoke(sessionId: String, was: PendingInteractionKind) {
        val serverIds = knownServersBySession[sessionId].orEmpty()
        if (serverIds.isEmpty()) {
            AppLogger.w(TAG, "Cannot revoke notification for " + sessionId + ": no known server ownership")
            return
        }
        val kind = SessionNotificationKind.forPendingInteraction(was)
        // #337：撤除目标镜像发布侧冒泡口径（子会话 → 父槽，共享映射函数）；
        // 父归属凭只增快照解析（删除级联时 sessions 行先于 store 清除）
        val targetSessionId = bubbleToParentSessionTarget(sessionId, knownChildSessions.values.toList())
        serverIds.forEach { serverId ->
            appNotificationManager.cancelInteractionNotifications(serverId, targetSessionId, kind)
        }
        AppLogger.i(TAG, "Revoked " + kind.name + " notification for session " + sessionId + " (target=" + targetSessionId + ", servers=" + serverIds + ")")
    }
}
