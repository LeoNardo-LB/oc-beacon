package dev.leonardo.ocbeacon.fakes

import dev.leonardo.ocbeacon.domain.model.FsmEvent
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.SessionActivity
import dev.leonardo.ocbeacon.domain.model.SessionFSMState
import dev.leonardo.ocbeacon.domain.model.SessionStateFSM
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.TransitionRecord
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模拟真实 [dev.leonardo.ocbeacon.data.repository.SessionStateService] 状态机行为的 fake。
 *
 * 复用纯函数 [SessionStateFSM] 维护会话 FSM 状态，使 statusFlow/activityFlow
 * 能像真实实现一样对 onClientSendParts/onClientAbort 等事件作出响应。
 * 由 FakeDomainModule 绑定到 [SessionStateRepository]，与 ViewModel 共享同一
 * @Singleton 实例。
 */
@Singleton
class FakeSessionStateRepository @Inject constructor() : SessionStateRepository {

    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())

    private val _statusFlow = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    private val _activityFlow = MutableStateFlow<Map<String, SessionActivity?>>(emptyMap())
    private val _historyFlow = MutableStateFlow<Map<String, List<TransitionRecord>>>(emptyMap())

    override val statusFlow: StateFlow<Map<String, SessionStatus>> = _statusFlow.asStateFlow()
    override val activityFlow: StateFlow<Map<String, SessionActivity?>> = _activityFlow.asStateFlow()
    override val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = _historyFlow.asStateFlow()

    val requestedValidations = mutableListOf<String>()

    override fun setServerId(serverId: String) = Unit

    override fun requestValidation(sessionId: String) {
        requestedValidations.add(sessionId)
    }

    override fun reconcileWithActiveSessions(activeIds: Set<String>) {
        // 2026-08-16：接口新增（active 轮询双向对账）——Fake 空实现
    }

    override fun onClientSendParts(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientSendParts)

    override fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)

    override fun onRestValidation(sessionId: String, status: SessionStatus) =
        applyTransition(sessionId, FsmEvent.RestValidation(status))

    private fun applyTransition(sessionId: String, event: FsmEvent) {
        _fsmStates.update { states ->
            val current = states[sessionId] ?: SessionFSMState.initial()
            val result = SessionStateFSM.transition(current, event)
            states + (sessionId to result.newState)
        }
        syncDerivedFlows()
    }

    private fun syncDerivedFlows() {
        val states = _fsmStates.value
        _statusFlow.value = states.mapValues { it.value.core }
        _activityFlow.value = states.mapValues { it.value.activity }
    }

    override fun clearSession(sessionId: String) {
        _fsmStates.update { it - sessionId }
        _historyFlow.update { it - sessionId }
        syncDerivedFlows()
    }

    override fun clearForServer(sessionIds: Set<String>) {
        _fsmStates.update { it - sessionIds }
        _historyFlow.update { it - sessionIds }
        syncDerivedFlows()
    }

    override fun clearAll() {
        _fsmStates.value = emptyMap()
        _historyFlow.value = emptyMap()
        syncDerivedFlows()
    }

    override suspend fun syncFromRest(projects: List<Project>): SyncResult =
        SyncResult(totalSessions = 0, busyCount = 0)
}
