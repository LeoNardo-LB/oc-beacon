package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import kotlinx.coroutines.CoroutineScope
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

    init {
        // ② 轮次结束兜底：store 侧订阅状态流（只读消费——FSM 写入路径零接触，
        // 承重规则：不重新引入按 handler 维护的状态、不改 SessionStateFSM）。
        appScope.launch {
            var prev: Map<String, SessionStatus> = emptyMap()
            sessionStateRepository.statusFlow.collect { statuses ->
                statuses.forEach { (sessionId, status) ->
                    val was = prev[sessionId]
                    if (status is SessionStatus.Idle && was != null && was !is SessionStatus.Idle) {
                        clearForSession(sessionId)
                    }
                }
                prev = statuses
            }
        }
    }

    /** 分发点旁路记录（所有权去重后调用——双配置同后端只记一次）。 */
    fun record(sessionId: String, kind: PendingInteractionKind) {
        _pendingBySession.update { it + (sessionId to kind) }
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
    }

    fun clearForSession(sessionId: String) {
        _pendingBySession.update { it - sessionId }
    }

    fun clearAll() {
        _pendingBySession.value = emptyMap()
    }
}
