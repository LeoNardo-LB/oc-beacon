package dev.leonardo.ocbeacon.data.api.session

import dev.leonardo.ocbeacon.data.api.RestSessionStatusInfo
import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionSearchResult

private const val TAG = "SessionApi"

interface SessionApi {
    suspend fun listSessions(
        conn: ServerConnection,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): List<Session>

    /**
     * 分页形态的会话列表（#273）：与 [listSessions] 同参，但携带服务器权威
     * nextCursor——分页调用方必须使用本方法，避免伪造游标触发服务器 400。
     */
    suspend fun listSessionsPage(
        conn: ServerConnection,
        directory: String? = null,
        search: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): dev.leonardo.ocbeacon.domain.model.SessionPage

    suspend fun getSession(conn: ServerConnection, sessionId: String): Session

    /** 以原始 JSON 字符串返回会话信息（用于导出而无需重新序列化）。 */
    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String

    suspend fun createSession(
        conn: ServerConnection,
        title: String? = null,
        parentId: String? = null,
        directory: String? = null,
        /** #311 ①-d：DSH V012 SessionCreateRequest.workspaceId（指定入组 workspace）；其余后端忽略。 */
        workspaceId: String? = null,
        /** #354：DSH V012 SessionCreateRequest.agentPreset（创建即带预设）；其余后端忽略。 */
        agentPreset: String? = null
    ): Session

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean

    suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Session

    /**
     * 用任意字段更新会话（用于归档等）。
     * PATCH /session/{sessionId}
     */
    suspend fun updateSessionFields(
        conn: ServerConnection,
        sessionId: String,
        fields: Map<String, Any>
    ): Session

    suspend fun interruptSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff>

    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session

    suspend fun compactSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean

    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session

    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session

    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String? = null): Session

    suspend fun importSession(conn: ServerConnection, shareUrl: String): Session

    suspend fun executeCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        arguments: String = "",
        directory: String? = null
    ): Boolean

    suspend fun listSessionChildren(conn: ServerConnection, sessionId: String): List<Session>

    /**
     * 权限预设切换（DSH 专属：commands/execute 通道 /permission <preset> 命令）。
     * OpenCode V1/V2 无对应域 → 默认 false（UI 按能力位 permissionSwitchSupported 隐藏）。
     */
    suspend fun setPermissionPreset(conn: ServerConnection, sessionId: String, preset: String): Boolean = false

    /**
     * DSH agentPreset.list roster（Agent 预设卡 / 设置页默认档选项）。
     * OpenCode V1/V2 无对应域 → 空列表（UI 按能力位 agentPresetSupported 隐藏）。
     */
    suspend fun listAgentPresets(conn: ServerConnection): List<AgentPreset> = emptyList()

    /**
     * DSH agentPreset.select（切换当前会话预设；非 blank → agent-preset-locked）。
     * OpenCode V1/V2 无对应域 → false（UI 按能力位隐藏）。错误上抛供锁定提示。
     */
    suspend fun selectAgentPreset(conn: ServerConnection, sessionId: String, presetId: String): Boolean = false

    /**
     * 排队项变更（#356 双面）：DSH updateQueue（edit/remove/steer）；
     * V2 inbox 变更（remove=DELETE /inbox/{id}、steer=POST /inbox/{id}/steer、
     * edit 无动词 → Failed——UI 按 queueEditSupported 隐藏）。
     * V1 无队列域 → Failed(unsupported)。错误码映射见各客户端。
     */
    suspend fun updateQueue(
        conn: ServerConnection,
        sessionId: String,
        itemId: String,
        action: dev.leonardo.ocbeacon.domain.model.QueueActionKind,
        editText: String? = null,
    ): dev.leonardo.ocbeacon.domain.model.QueueMutationResult =
        dev.leonardo.ocbeacon.domain.model.QueueMutationResult.Failed("updateQueue unsupported")

    /**
     * #356 V2 inbox 排队列表（GET /api/session/{id}/inbox → type=user 项）。
     * DSH 队列经 session/queue 控制帧推送（DshQueueStore 单一真相源）→ null；
     * V1 无可见域 → null。失败亦 null（调用方保旧值）。
     */
    suspend fun listInbox(
        conn: ServerConnection,
        sessionId: String,
    ): List<dev.leonardo.ocbeacon.domain.model.QueuedInboxItem>? = null

    /** DSH goal.create（创建并 arm 目标；maxGoalRounds 可选）。回执 value.ref = 新 CAS ref。
     *  OpenCode V1/V2 无 goal 域 → null（UI 按能力位 goalSupported 隐藏）。 */
    suspend fun goalCreate(
        conn: ServerConnection,
        sessionId: String,
        objective: String,
        maxGoalRounds: Long? = null,
    ): DshGoalRef? = null

    /** DSH goal.edit（改 objective/maxGoalRounds 之一或两者；CAS ref 取自当前投影）。 */
    suspend fun goalEdit(
        conn: ServerConnection,
        sessionId: String,
        ref: DshGoalRef,
        objective: String? = null,
        maxGoalRounds: Long? = null,
    ): DshGoalRef? = null

    /** DSH goal.pause（暂停 active 目标并 disarm 自动延续）。 */
    suspend fun goalPause(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? = null

    /** DSH goal.resume（恢复 paused 目标并重新 arm）。 */
    suspend fun goalResume(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? = null

    /** DSH goal.complete（完成当前目标并 disarm）。 */
    suspend fun goalComplete(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? = null

    /** DSH goal.clear（清除当前目标，保留 durable tombstone；回执 {cleared:true}）。 */
    suspend fun goalClear(conn: ServerConnection, sessionId: String, ref: DshGoalRef): Boolean = false

    /**
     * DSH subagent.list 权威子代理目录（AgentSheet 多级树逐层懒加载）。
     * 非 DSH 服务器（V1/V2 无该域）返回 null——调用方走本地 session 镜像递归；
     * DSH 业务错误（HTTP 200 + result.error）上抛供软降级判定。
     */
    suspend fun listSubagentCatalog(
        conn: ServerConnection,
        parentSessionId: String,
    ): List<dev.leonardo.ocbeacon.data.dto.response.SubagentListEntryDto>? = null

    /**
     * DSH 服务端内容搜索（session/search，backlog #322）：按名字+内容搜全部历史会话。
     * OpenCode V1/V2 无对应域 → 默认 null（端点缺席语义，#314 先例——UI 走本地 FTS 零外溢）。
     */
    suspend fun searchSessions(conn: ServerConnection, query: String): SessionSearchResult? = null

    suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<TodoItem>

    /**
     * 将当前会话所有前台可后台化工具（subagent）批量转为后台（V2）。
     * V1 不支持（返回 false）。
     */
    suspend fun backgroundSession(conn: ServerConnection, sessionId: String): Boolean

    /**
     * 活跃会话查询（V2）：返回前台活跃会话 ID → 类型（"running" 等）。
     * V1 不支持（返回空）。
     */
    suspend fun activeSessions(conn: ServerConnection): Map<String, ActiveSessionInfo>

    suspend fun listSessionStatus(conn: ServerConnection, directory: String? = null): Map<String, SessionStatusInfo>

    suspend fun fetchSessionStatus(
        conn: ServerConnection,
        directory: String? = null
    ): Result<Map<String, RestSessionStatusInfo>>
}
