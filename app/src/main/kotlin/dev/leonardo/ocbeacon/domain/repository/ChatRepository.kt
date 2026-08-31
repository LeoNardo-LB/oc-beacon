package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PermissionState
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.StepProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import kotlinx.coroutines.flow.Flow

/**
 * 聊天操作的 Repository 接口。
 * 实现归属：由 data 层实现（domain 层仅声明契约）。
 */
interface ChatRepository {

    // ============ 状态观察 ============

    /**
     * 观察某个会话的消息列表（含 parts）。
     * 实现：委托给 EventDispatcher.messages，并映射为领域 Message（data 层装配）。
     */
    fun getMessagesFlow(sessionId: String): Flow<List<Message>>

    /**
     * 观察某个会话的 parts 列表。
     */
    fun getParts(sessionId: String): Flow<List<Part>>

    /**
     * 观察所有会话的 parts 映射（sessionId → parts）。
     * 供组装每条消息 ChatMessage 对象的 combine 使用。
     */
    fun getAllPartsMap(): Flow<Map<String, List<Part>>>

    /**
     * 观察某个会话待处理的权限请求列表。
     */
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>>

    /**
     * 观察某个会话待处理的问题列表。
     */
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>>

    /**
     * 观察所有会话的原始问题映射（sessionId → list）。
     * 供在问题变更时需要响应式重计算的 combine 使用。
     */
    fun getAllQuestionsFlow(): Flow<Map<String, List<SseEvent.QuestionAsked>>>

    /**
     * 观察所有会话的原始权限映射（sessionId → list）。
     * 供在权限变更时需要响应式重计算的 combine 使用。
     */
    fun getAllPermissionsFlow(): Flow<Map<String, List<SseEvent.PermissionAsked>>>

    // ============ EventDispatcher Flow 暴露 ============

    /**
     * 观察某台服务器上正在进行的工具进度。
     */
    fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?>

    /**
     * 观察某台服务器上的步骤进度。
     */
    fun getStepProgress(serverId: String): Flow<StepProgressInfo?>

    /**
     * 观察某台服务器上的压缩状态。
     */
    fun getCompactionState(serverId: String): Flow<CompactionStateInfo?>

    // ============ 网络操作 ============

    /**
     * 向指定会话发送消息（parts 列表）。
     * 成功时返回结果 [Message]，失败时返回异常。
     */
    suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message>

    /**
     * 按 ID 回复问题。
     */
    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean>

    /**
     * 异步发送 prompt（触发后即忘）。
     */
    suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    ): Result<Unit>

    /**
     * 从指定 messageId 开始撤销（revert）消息。
     */
    suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit>

    /**
     * 在会话中取消撤销（unrevert/redo）最近一次被撤销的消息。
     */
    suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * 回复权限请求（带服务器上下文）。
     */
    suspend fun respondPermission(
        serverId: String,
        /** 2026-08-17：V2 reply 路由需要——权限所属会话 id。 */
        sessionId: String,
        permissionId: String,
        reply: String,
        directory: String? = null
    ): Result<Boolean>

    // ============ 待处理查询 ============

    /**
     * 列出某台服务器上待处理的权限请求。
     */
    suspend fun listPendingPermissions(serverId: String, directory: String? = null): Result<List<PermissionState>>

    /**
     * 列出某台服务器上待处理的问题请求。
     */
    suspend fun listPendingQuestions(serverId: String, directory: String? = null): Result<List<QuestionState>>

    /**
     * 以多个答案回复问题请求。
     */
    suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Result<Boolean>

    /**
     * 拒绝问题请求。
     */
    suspend fun rejectQuestion(
        serverId: String,
        requestId: String,
        directory: String? = null
    ): Result<Boolean>

    // ============ 命令执行 ============

    /**
     * 在会话中执行服务端命令。
     * 可选 agent/model/variant/parts 按 V1 契约进请求体（model 为
     * "providerID/modelID" 字符串格式，与 prompt 的对象格式不同）。
     */
    suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String = "",
        directory: String? = null,
        agent: String? = null,
        model: String? = null,
        variant: String? = null,
        parts: List<Map<String, String>>? = null
    ): Result<Boolean>

    /**
     * 切换当前会话权限预设（DSH 专属：/permission <preset> 命令）。
     * OpenCode V1/V2 返回成功 false（能力位 permissionSwitchSupported 隐藏入口）。
     */
    suspend fun setPermissionPreset(serverId: String, sessionId: String, preset: String): Result<Boolean>

    /**
     * DSH Agent 预设 roster（agentPreset.list）；OpenCode V1/V2 空列表。
     */
    suspend fun listAgentPresets(serverId: String): Result<List<AgentPreset>>

    /**
     * DSH 切换当前会话 Agent 预设（agentPreset.select）；非 blank → agent-preset-locked。
     * 失败经 Result 上抛（调用方按 DshApiError.category 映射锁定提示）。
     */
    suspend fun selectAgentPreset(serverId: String, sessionId: String, presetId: String): Result<Boolean>


    // ============ DSH goal 六 mutation（backlog #286；OpenCode V1/V2 返回 null/false） ============

    /** DSH goal.create（创建并 arm 目标）。回执新 CAS ref；失败经 Result 上抛（DshApiError）。 */
    suspend fun createGoal(
        serverId: String,
        sessionId: String,
        objective: String,
        maxGoalRounds: Long?,
    ): Result<DshGoalRef?>

    /** DSH goal.edit（改 objective/maxGoalRounds；CAS ref 取自当前投影）。 */
    suspend fun editGoal(
        serverId: String,
        sessionId: String,
        ref: DshGoalRef,
        objective: String?,
        maxGoalRounds: Long?,
    ): Result<DshGoalRef?>

    /** DSH goal.pause。 */
    suspend fun pauseGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<DshGoalRef?>

    /** DSH goal.resume。 */
    suspend fun resumeGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<DshGoalRef?>

    /** DSH goal.complete。 */
    suspend fun completeGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<DshGoalRef?>

    /** DSH goal.clear（回执 {cleared:true}）。 */
    suspend fun clearGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<Boolean>

    /**
     * 在会话中运行 shell 命令。
     */
    suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String? = null,
        modelId: String? = null,
        directory: String? = null
    ): Result<Boolean>

    // ============ 后台活动（V2） ============

    /**
     * 将当前会话所有前台可后台化工具（subagent）批量转为后台（V2）。
     */
    suspend fun backgroundSession(serverId: String, sessionId: String): Result<Boolean>

    /**
     * 前台活跃会话列表（V2 /api/session/active 轮询）——运行中会话的权威来源。
     */
    suspend fun listActiveSessions(serverId: String): Result<Map<String, ActiveSessionInfo>>

    /**
     * 列出运行中的后台 shell 命令（V2）。
     */
    suspend fun listShells(serverId: String, directory: String? = null): Result<List<ShellJob>>

    /**
     * 分页读取后台 shell 输出（V2）。
     */
    suspend fun getShellOutput(
        serverId: String,
        shellId: String,
        cursor: Long? = null,
        limit: Int? = null,
        directory: String? = null
    ): Result<ShellOutput?>

    /**
     * 终止并删除后台 shell（V2）。
     */
    suspend fun removeShell(serverId: String, shellId: String, directory: String? = null): Result<Boolean>

    // ============ 权限自动批准 ============

    /**
     * 持久化一条新的权限自动批准规则（用户选择了"始终批准"）。
     */
    suspend fun addPermissionAutoApproveRule(rule: AutoApproveRule)

    // ============ 写入操作（状态更新）============

    /**
     * 统一批量合并入口。三策略覆盖原 [setMessages]/[mergeMessages]/[replaceMessages]：
     * - [MergeStrategy.SSE_PRIORITY] ← setMessages（REST 刷新/进入会话，SSE 优先）
     * - [MergeStrategy.APPEND_ONLY] ← mergeMessages（翻页加载更早，仅补充缺失）
     * - [MergeStrategy.REST_AUTHORITY] ← replaceMessages（SSE 重连恢复，REST 真相源）
     */
    fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        strategy: MergeStrategy,
    )


    /**
     * 清除某个会话的撤销状态。
     * 在用户撤销后发送新消息时调用——服务器会消费撤销，
     * 但可能不会通过 SSE 通知客户端。
     */
    fun clearRevert(sessionId: String)

    /** 在 REST 撤销之后立即设置本地撤销状态（防止旧消息闪现）。 */
    fun setRevert(sessionId: String, messageId: String)

    /**
     * 按 ID 移除权限卡片（回复后的乐观移除）。
     */
    fun removePermission(permissionId: String)

    /**
     * 设置某个会话的权限（REST 合并）。
     */
    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>)

    /**
     * 按 ID 移除问题卡片（回复后的乐观移除）。
     */
    fun removeQuestion(questionId: String)

    /**
     * 设置某个会话的问题（REST 合并）。
     */
    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>)

    /**
     * 聚合某个会话及其子智能体会话的权限。
     */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.PermissionAsked>

    /**
     * 聚合某个会话及其子智能体会话的问题。
     */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.QuestionAsked>

    // ============ 原始状态读取（用于复杂的读写模式）============

    /**
     * 读取当前的权限映射快照。
     * 供 REST 合并逻辑使用——在合并前读取现有 SSE 状态。
     */
    fun getPermissionsSnapshot(): Map<String, List<SseEvent.PermissionAsked>>

    /**
     * 读取当前的问题映射快照。
     * 供 REST 合并逻辑使用——在合并前读取现有 SSE 状态。
     */
    fun getQuestionsSnapshot(): Map<String, List<SseEvent.QuestionAsked>>

    /**
     * 读取当前的会话列表快照。
     * 供 REST 合并逻辑使用——查找子智能体会话和标题。
     */
    fun getSessionsSnapshot(): List<Session>

    /**
     * 观察某个特定会话（以 sessionId 为键）的工具进度。
     */
    fun getActiveToolProgressForSession(sessionId: String): Flow<List<ToolProgressInfo>?>

    /**
     * 观察某个特定会话（以 sessionId 为键）的步骤进度。
     */
    fun getStepProgressForSession(sessionId: String): Flow<StepProgressInfo?>

    /**
     * 观察某个特定会话（以 sessionId 为键）的压缩状态。
     */
    fun getCompactionStateForSession(sessionId: String): Flow<CompactionStateInfo?>

    /**
     * 观察某个特定会话（以 sessionId 为键）的文件差异。
     * 支撑 [dev.leonardo.ocbeacon.domain.model.Part.Patch] 的行数统计。
     */
    fun getSessionDiffsForSession(sessionId: String): Flow<List<FileDiff>>
}