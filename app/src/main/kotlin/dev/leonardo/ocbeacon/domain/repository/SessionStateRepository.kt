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

    /** #176/#177：会话归属服务器解析（SSE 投递记录优先，回退当前服务器）。 */
    fun serverIdFor(sessionId: String): String?

    /** #176/#177：会话是否有待答问题/权限（堆积 drain 护栏）。 */
    fun hasPendingUserInput(sessionId: String): Boolean

    /** 请求对该会话执行 REST 状态校验。 */
    fun requestValidation(sessionId: String)

    /**
     * 2026-08-16（会话状态对账）：active 轮询结果与 FSM 双向对账——
     * 正向（active 含但 FSM 非 Busy → L3 恢复，修 SSE 断连丢 execution.started）
     * + 反向（FSM Busy 但 active 缺失且事件陈旧 → L3 僵尸自愈）。
     * 空集直接返回（V1 active 恒空——无信息不判定）。
     */
    fun reconcileWithActiveSessions(activeIds: Set<String>)

    /**
     * 2026-08-16 根治（回复不可见）：SSE 断连窗口消息补漏——cursor 增量
     * 拉取 + SSE_PRIORITY 合并（流式进行中安全）。触发点：ON_RESUME、
     * SSE 重连成功。不碰 FSM，仅补内容。
     */
    fun backfillMissedMessages(sessionId: String)

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
