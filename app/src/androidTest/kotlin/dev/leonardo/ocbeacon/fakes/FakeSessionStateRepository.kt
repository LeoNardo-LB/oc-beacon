package dev.leonardo.ocbeacon.fakes

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.SessionActivity
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.TransitionRecord
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 最小 fake：状态流可注入、操作被记录，用于替换 SessionStateService。 */
@Singleton
class FakeSessionStateRepository @Inject constructor() : SessionStateRepository {

    override val statusFlow: StateFlow<Map<String, SessionStatus>> = MutableStateFlow(emptyMap())
    override val activityFlow: StateFlow<Map<String, SessionActivity?>> = MutableStateFlow(emptyMap())
    override val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = MutableStateFlow(emptyMap())

    val requestedValidations = mutableListOf<String>()

    override fun setServerId(serverId: String) = Unit

    override fun requestValidation(sessionId: String) {
        requestedValidations.add(sessionId)
    }

    override fun onClientSendParts(sessionId: String) = Unit

    override fun onClientAbort(sessionId: String) = Unit

    override fun onRestValidation(sessionId: String, status: SessionStatus) = Unit

    override fun clearSession(sessionId: String) = Unit

    override fun clearForServer(sessionIds: Set<String>) = Unit

    override fun clearAll() = Unit

    override suspend fun syncFromRest(projects: List<Project>): SyncResult =
        SyncResult(totalSessions = 0, busyCount = 0)
}
