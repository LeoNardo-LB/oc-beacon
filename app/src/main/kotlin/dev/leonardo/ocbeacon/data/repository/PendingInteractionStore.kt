package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #311 Task4：会话行待交互指示种类（对齐 web pendingInteraction 三值域，
 * wire 契约④ mod29:384-393）。
 */
enum class PendingInteractionKind {
    /** 等待权限批准（waterfall approval/request ↔ app PermissionAsked）。 */
    APPROVAL,
    /** 等待计划审阅（user-questions intent kind="plan-review"，#310③；呈现归并 question 同点）。 */
    PLAN_REVIEW,
    /** 等待回答提问（waterfall user-questions/request ↔ app QuestionAsked）。 */
    QUESTION,
}

/** question 呈现族：plan-review 归并 question 同点；行指示与 Asking 合流去重（勿双点）。 */
val PendingInteractionKind.isQuestionFamily: Boolean
    get() = this == PendingInteractionKind.QUESTION || this == PendingInteractionKind.PLAN_REVIEW

/**
 * #339：径② 清除延后复核窗口——重连 resync 重放历史的瞬态 Busy→Idle 边沿
 * （回放交错产物，间隔毫秒级）在窗口内被后续状态覆盖；真实轮末 Idle 持续
 * 在场不受影响。2s 兼顾回放交错跨度（实证 <50ms）与轮末清除时延感知。
 */
internal const val IDLE_CLEAR_SETTLE_MS = 2_000L

/**
 * 会话待交互（等待审批/提问）状态容器（#311 Task4，客户端本地域）。
 *
 * wire 契约④（2026-09-05）：真服务器不推 approvals/questions 状态——与 web
 * PendingInteractionDomain 同构，由请求事件（PermissionAsked/QuestionAsked，
 * 等价 waterfall approval/request 与 user-questions/request）在 EventDispatcher
 * 分发点旁路记录，会话行据此呈现指示。
 *
 * 形状对齐 [DshQueueStore]：StateFlow<Map<sessionId, PendingKind>>，瞬态不入
 * Room/历史。每会话单值 last-wins（web pendingInteraction 同为单值域）。
 *
 * 清除三径（勿增第四径——滞留兜底已覆盖）：
 * ① 本地应答成功：EventDispatcher.removePermission/removeQuestion 委托同点
 *    （auto approver ok 路径 #308 Layer2 与手动应答共用该委托）；
 * ② 轮次结束兜底：store 侧订阅 [SessionStateRepository.statusFlow]——会话
 *    转回 Idle 即清（Busy→Idle 为主；Asking/Retry→Idle 属同族轮次终点，如实
 *    扩展防滞留），只读消费、FSM 写入路径零接触（承重规则）；
 * ③ 会话删除级联：EventDispatcher SessionDeleted 分发点。
 *
 * 已知取舍：他端应答的 Replied 广播帧不清本域（web 同为请求驱动单值域），
 * 残留指示由 ② 轮次结束兜底收敛。
 */
@Singleton
class PendingInteractionStore @Inject constructor(
    private val sessionStateRepository: SessionStateRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val _pendingBySession = MutableStateFlow<Map<String, PendingInteractionKind>>(emptyMap())

    /** sessionId → 待交互种类（单值 last-wins）。 */
    val pendingBySession: StateFlow<Map<String, PendingInteractionKind>> = _pendingBySession.asStateFlow()

    /**
     * #336：sessionId → 已通知槽（该会话当前挂起等待态的系统通知已发布过）。
     *
     * 键空间与 [pendingBySession] 一致（原始事件 sessionId）——生命周期严格随
     * pending 条目联动，由本类清除路径同点复位：
     * - pending 清除（三径任一）/ kind 切换 → 槽复位（下一轮等待态可再通知）；
     * - 同 kind 再记录（SSE 冷启重放/追加同类请求）→ 槽保留（单值域等待态
     *   延续——防重放清槽导致退后台重复补发）。
     *
     * 写入方：到达发布点（SessionNotificationCoordinator 通知后）与退后台补发点
     * （PendingInteractionBackgroundNotifier 补发后）——「已发过的不重发」判定源。
     */
    private val notifiedBySession = MutableStateFlow<Map<String, PendingInteractionKind>>(emptyMap())

    init {
        // ② 轮次结束兜底：store 侧订阅状态流（只读消费——FSM 写入路径零接触，
        // 承重规则：不重新引入按 handler 维护的状态、不改 SessionStateFSM）。
        //
        // #339（伪 Idle 边沿防御——延后复核）：重连 resync 重放历史时，回放的
        // 旧 turn/end 会产生瞬态 Busy→Idle 边沿（真机实证 21:06:59.723 Idle →
        // .725 又回 Busy/Waiting——回放交错，终态仍 Busy），即时清 pending 会
        // 误撤仍挂起的问题通知（Revoker 同点撤除，用户失去提醒）。改为延后
        // [IDLE_CLEAR_SETTLE_MS] 复核：届时仍 Idle 才清（真轮末 Idle 持续在场；
        // 回放瞬态边沿已被后续 Busy 覆盖）。径①/径③ 仍即时，不受影响。
        appScope.launch {
            var prev: Map<String, SessionStatus> = emptyMap()
            sessionStateRepository.statusFlow.collect { statuses ->
                statuses.forEach { (sessionId, status) ->
                    val was = prev[sessionId]
                    if (status is SessionStatus.Idle && was != null && was !is SessionStatus.Idle) {
                        appScope.launch {
                            delay(IDLE_CLEAR_SETTLE_MS)
                            if (sessionStateRepository.statusFlow.value[sessionId] is SessionStatus.Idle) {
                                clearForSession(sessionId)
                            }
                        }
                    }
                }
                prev = statuses
            }
        }
    }

    /** 分发点旁路记录（所有权去重后调用——双配置同后端只记一次）。 */
    fun record(sessionId: String, kind: PendingInteractionKind) {
        _pendingBySession.update { it + (sessionId to kind) }
        // #336：kind 切换 = 新等待态 → 已通知槽复位（通知机会重置）；
        // 同 kind 再记录保留槽（等待态延续，防重放重复补发）。
        notifiedBySession.update { notified ->
            if (notified.containsKey(sessionId) && notified[sessionId] != kind) {
                notified - sessionId
            } else {
                notified
            }
        }
    }

    /** 清除①同点调用：仅当记录种类同族时移除（question 族互清——plan-review 归并）。 */
    fun clearIfKind(sessionId: String, kind: PendingInteractionKind) {
        _pendingBySession.update { all ->
            val current = all[sessionId]
            if (current != null && (current == kind || (current.isQuestionFamily && kind.isQuestionFamily))) {
                all - sessionId
            } else {
                all
            }
        }
        clearNotifiedIfPendingAbsent(sessionId)
    }

    fun clearForSession(sessionId: String) {
        _pendingBySession.update { it - sessionId }
        clearNotifiedIfPendingAbsent(sessionId)
    }

    fun clearAll() {
        _pendingBySession.value = emptyMap()
        notifiedBySession.value = emptyMap()
    }

    // ============ #336：已通知槽 ============

    /** 系统通知发布后同点标记（到达发布点/退后台补发点调用）。 */
    fun markNotified(sessionId: String, kind: PendingInteractionKind) {
        notifiedBySession.update { it + (sessionId to kind) }
    }

    /** 该会话当前挂起等待态是否已发过通知（补发去重判定）。 */
    fun isNotified(sessionId: String): Boolean = notifiedBySession.value.containsKey(sessionId)

    /** 槽随 pending 条目联动复位：pending 已不在 → 槽一并清除。 */
    private fun clearNotifiedIfPendingAbsent(sessionId: String) {
        if (!_pendingBySession.value.containsKey(sessionId)) {
            notifiedBySession.update { it - sessionId }
        }
    }
}
