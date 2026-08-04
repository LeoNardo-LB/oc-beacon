package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.SessionActivity
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.TransitionRecord
import kotlinx.coroutines.flow.StateFlow

/** REST → FSM 同步的结果（手动刷新时观察）。 */
data class SyncResult(val totalSessions: Int, val busyCount: Int)

/**
 * 会话状态与流式活动的领域层接口（UI 视角）。
 *
 * 实现为 [dev.leonardo.ocbeacon.data.repository.SessionStateService]（单一真相源）。
 * 仅暴露 UI/ViewModel 需要的读状态与客户端事件通知；SSE 注入、REST 同步等
 * data 层内部机制不在此接口中。
 */
interface SessionStateRepository {

    /** 每台服务器的会话状态（idle/busy/retry）。 */
    val statusFlow: StateFlow<Map<String, SessionStatus>>

    /** 每台服务器的流式活动（Waiting/Streaming/ToolCalling）。 */
    val activityFlow: StateFlow<Map<String, SessionActivity?>>

    /** 每台服务器的 FSM 转移历史。 */
    val historyFlow: StateFlow<Map<String, List<TransitionRecord>>>

    /** 绑定当前服务器上下文。 */
    fun setServerId(serverId: String)

    /** 请求对该会话执行 REST 状态校验。 */
    fun requestValidation(sessionId: String)

    /** 客户端发送消息时通知 FSM。 */
    fun onClientSendParts(sessionId: String)

    /** 客户端中断时通知 FSM。 */
    fun onClientAbort(sessionId: String)

    /** REST 校验结果回调。 */
    fun onRestValidation(sessionId: String, status: SessionStatus)

    /** 清理单个会话的状态。 */
    fun clearSession(sessionId: String)

    /** 清理某台服务器全部会话的状态。 */
    fun clearForServer(sessionIds: Set<String>)

    /** 清理全部会话状态。 */
    fun clearAll()

    /** 从 REST 同步会话状态（手动刷新时调用）。 */
    suspend fun syncFromRest(projects: List<Project>): SyncResult
}
