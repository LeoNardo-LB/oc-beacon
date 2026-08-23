package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.CreateSessionOpts
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * 会话管理的 Repository 接口。
 * 实现归属：由 data 层实现（domain 层仅声明契约）。
 */
interface SessionRepository {

    // ============ 状态观察 ============

    /**
     * 观察某个服务器连接的会话列表。
     * 实现：委托给按 serverId 过滤的 EventDispatcher.sessions（data 层装配）。
     */
    fun getSessionsFlow(serverId: String): Flow<List<Session>>

    /**
     * 观察某个服务器连接的会话状态。
     * 委托给 EventDispatcher.sessionStatuses。
     */
    fun getSessionStatusesFlow(serverId: String): Flow<Map<String, SessionStatus>>

    /**
     * 观察全局 服务器→会话id集合 映射（跨所有服务器）。
     * 委托给 EventDispatcher.serverSessions。供 UI 聚合状态按 serverId 切片使用。
     */
    fun getServerSessionsFlow(): Flow<Map<String, Set<String>>>

    /**
     * 观察全局 会话id→最近用户消息时间 映射（跨所有服务器）。
     * 委托给 EventDispatcher.lastUserMessageTime。供会话排序使用。
     */
    fun getLastUserMessageTimeFlow(): Flow<Map<String, Long>>

    /**
     * 观察全局 会话id→最近回复完成时间 映射（跨所有服务器，服务器 completed 时刻）。
     * 委托给 EventDispatcher.lastCompletedReplyTime。供未读提示判定使用。
     */
    fun getLastCompletedReplyTimeFlow(): Flow<Map<String, Long>>

    // ============ CRUD ============

    /**
     * 通过 REST 列出服务器上的会话（支持按目录/搜索/游标分页）。
     * 委托给 SessionApi.listSessions。
     */
    suspend fun listSessions(
        serverId: String,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): List<Session>

    /**
     * 在指定服务器上使用给定选项创建新会话。
     * 成功时返回创建的 [Session]。
     */
    suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session>

    /**
     * 按 ID 删除会话。
     */
    suspend fun deleteSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * 按 ID 获取单个会话。
     */
    suspend fun getSession(serverId: String, sessionId: String): Result<Session>

    // ============ 会话生命周期 ============

    /**
     * 中止运行中的会话。
     */
    suspend fun abort(serverId: String, sessionId: String, directory: String?): Result<Unit>

    /**
     * 重命名会话。
     */
    suspend fun rename(serverId: String, sessionId: String, title: String): Result<Unit>

    /**
     * 分叉会话，从某条消息处创建新会话。
     */
    suspend fun fork(serverId: String, sessionId: String): Result<Session>

    // ============ 归档 ============

    /**
     * 归档会话。
     */
    suspend fun archive(serverId: String, sessionId: String): Result<Session>

    /**
     * 取消归档会话。
     */
    suspend fun unarchive(serverId: String, sessionId: String): Result<Session>

    // ============ 分享 / 导出 ============

    /**
     * 分享会话，创建可分享链接。
     */
    suspend fun shareSession(serverId: String, sessionId: String): Result<Session>

    /**
     * 取消分享会话，移除可分享链接。
     */
    suspend fun unshareSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * 摘要（压缩）会话以减少上下文。
     */
    suspend fun compactSession(serverId: String, sessionId: String, providerId: String, modelId: String): Result<Unit>

    /**
     * 将会话导出 JSON 直接流式写入 OutputStream。
     * @param onProgress 回调，参数为目前已写入的字节数。
     */
    suspend fun exportSessionToStream(
        serverId: String,
        sessionId: String,
        outputStream: OutputStream,
        onProgress: (Long) -> Unit
    ): Result<Unit>

    // ============ 导入 ============

    /**
     * 从分享 URL 导入会话。
     */
    suspend fun importSession(serverId: String, shareUrl: String): Result<Session>

    // ============ 消息操作 ============

    /**
     * 从会话中删除消息。
     */
    suspend fun deleteMessage(serverId: String, sessionId: String, messageId: String): Result<Boolean>

    /**
     * 按索引从消息中删除某个 part。
     */
    suspend fun deleteMessagePart(serverId: String, sessionId: String, messageId: String, partIndex: Int): Result<Boolean>

    /**
     * 列出会话中的消息。
     */
    suspend fun listMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        before: String? = null,
    ): Result<MessagePage>

    /**
     * 获取单条消息（快速导航定位 target 用）。
     * V2: GET /api/session/{id}/message/{messageId}；V1: GET /session/{id}/message/{messageId}。
     */
    suspend fun getMessage(
        serverId: String,
        sessionId: String,
        messageId: String,
    ): Result<dev.leonardo.ocbeacon.domain.model.MessageWithParts>

    /**
     * 服务器 API 版本（决定游标构造格式：V2 用 {id,order,direction}，V1 用 {id,time}）。
     */
    suspend fun getApiVersion(serverId: String): dev.leonardo.ocbeacon.domain.model.ApiVersion

    // ============ 当前 Agent/Model（SSE session.next）============

    /**
     * 观察来自 SSE session.next 事件的当前 agent 名称映射（sessionId → agent 名称）。
     */
    fun getCurrentAgentFlow(serverId: String): Flow<Map<String, String>>

    /**
     * 观察来自 SSE session.next 事件的当前 model 映射（sessionId → Pair(providerId, modelId)）。
     */
    fun getCurrentModelFlow(serverId: String): Flow<Map<String, Pair<String, String>>>

    // ============ 写入操作（状态更新）============

    /**
     * 将 REST 加载的会话注入状态持有者。
     * 用于 REST 回退加载会话信息、早于 SSE 投递的场景。
     */
    fun setSessions(serverId: String, sessions: List<Session>)

    // ============ TODO（2026-08-20 堆积/TODO 面板） ============

    /**
     * 获取会话 TODO。成功：回填 hydrate 缓存（EventDispatcher.sessionTodos）；
     * 服务器无端点（V2 beta 实测 404）→ failure，调用方据此隐藏 TODO tab。
     */
    suspend fun getSessionTodos(serverId: String, sessionId: String): Result<List<dev.leonardo.ocbeacon.domain.model.SseEvent.TodoUpdated.Todo>>

    // ============ 会话状态同步 ============

    /**
     * 通过 REST 从服务器拉取所有会话状态。
     * 在可能遗漏 SSE 事件时作为回退使用。
     * @return sessionId → [SessionStatus] 的映射。
     */
    suspend fun fetchSessionStatuses(serverId: String, directory: String? = null): Result<Map<String, SessionStatus>>
}
