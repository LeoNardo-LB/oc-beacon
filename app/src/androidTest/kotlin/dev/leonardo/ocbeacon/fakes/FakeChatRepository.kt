package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput
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
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.StepProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

/**
 * Fake ChatRepository，包含 49 个 override。
 *
 * 模式：
 * - Flow 方法返回公共的 MutableStateFlow 字段（测试设置 .value）
 * - suspend 方法返回可配置的 Result 字段（默认 = success）
 * - 同步变更方法记录调用 + 更新状态
 *
 * 与会话无关：所有 flow 方法不论 sessionId/serverId 都返回同一个 flow。
 */
@Singleton
class FakeChatRepository @Inject constructor() : ChatRepository {

    // ============ 可控 State Flow ============

    val messagesState = MutableStateFlow<List<Message>>(emptyList())
    val partsState = MutableStateFlow<List<Part>>(emptyList())
    val allPartsMapState = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val permissionsState = MutableStateFlow<List<PermissionState>>(emptyList())
    val questionsState = MutableStateFlow<List<QuestionState>>(emptyList())
    val allQuestionsMapState = MutableStateFlow<Map<String, List<SseEvent.QuestionAsked>>>(emptyMap())
    val allPermissionsMapState = MutableStateFlow<Map<String, List<SseEvent.PermissionAsked>>>(emptyMap())
    val toolProgressState = MutableStateFlow<List<ToolProgressInfo>?>(null)
    val stepProgressState = MutableStateFlow<StepProgressInfo?>(null)
    val compactionState = MutableStateFlow<CompactionStateInfo?>(null)
    val sessionDiffsState = MutableStateFlow<List<FileDiff>>(emptyList())

    // 同步变更的内部后备存储
    private val messagesStore = mutableMapOf<String, MutableList<MessageWithParts>>()
    private val toolExpandedStates = mutableMapOf<String, Boolean>()
    private val permissionsStore = mutableMapOf<String, MutableList<SseEvent.PermissionAsked>>()
    private val questionsStore = mutableMapOf<String, MutableList<SseEvent.QuestionAsked>>()
    private val revertStore = mutableMapOf<String, String>()
    private val autoApproveRules = mutableListOf<AutoApproveRule>()
    private var sessionsSnapshot: List<Session> = emptyList()

    // ============ 可配置 suspend Result ============

    var sendMessageResult: Result<Message> = Result.success(
        Message.User(
            id = "msg-default",
            sessionId = "test-session",
            time = dev.leonardo.ocbeacon.domain.model.TimeInfo(created = System.currentTimeMillis())
        )
    )
    var replyQuestionResult: Result<Boolean> = Result.success(true)
    var promptAsyncResult: Result<Unit> = Result.success(Unit)
    var revertResult: Result<Unit> = Result.success(Unit)
    var unrevertResult: Result<Unit> = Result.success(Unit)
    var respondPermissionResult: Result<Boolean> = Result.success(true)
    var listPendingPermissionsResult: Result<List<PermissionState>> = Result.success(emptyList())
    var listPendingQuestionsResult: Result<List<QuestionState>> = Result.success(emptyList())
    var replyToQuestionResult: Result<Boolean> = Result.success(true)
    var rejectQuestionResult: Result<Boolean> = Result.success(true)
    var executeCommandResult: Result<Boolean> = Result.success(true)
    var runShellCommandResult: Result<Boolean> = Result.success(true)

    // ============ 调用记录 ============

    val sentMessages = mutableListOf<Pair<String, List<Part>>>()
    val promptAsyncCalls = mutableListOf<Pair<String, List<PromptPart>>>()
    val repliedQuestions = mutableListOf<Pair<String, String>>()
    val executeCommandCalls = mutableListOf<Map<String, String>>()

    // ============ 状态观察 ============

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> = messagesState

    override fun getParts(sessionId: String): Flow<List<Part>> = partsState

    override fun getAllPartsMap(): Flow<Map<String, List<Part>>> = allPartsMapState

    override fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> = permissionsState

    override fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> = questionsState

    override fun getAllQuestionsFlow(): Flow<Map<String, List<SseEvent.QuestionAsked>>> = allQuestionsMapState

    override fun getAllPermissionsFlow(): Flow<Map<String, List<SseEvent.PermissionAsked>>> = allPermissionsMapState

    override fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?> = toolProgressState

    override fun getStepProgress(serverId: String): Flow<StepProgressInfo?> = stepProgressState

    override fun getCompactionState(serverId: String): Flow<CompactionStateInfo?> = compactionState

    // ============ 按 session 键的 Flow 观察 ============

    override fun getActiveToolProgressForSession(sessionId: String): Flow<List<ToolProgressInfo>?> = toolProgressState

    override fun getStepProgressForSession(sessionId: String): Flow<StepProgressInfo?> = stepProgressState

    override fun getCompactionStateForSession(sessionId: String): Flow<CompactionStateInfo?> = compactionState

    override fun getSessionDiffsForSession(sessionId: String): Flow<List<FileDiff>> = sessionDiffsState

    // ============ 网络操作 ============

    override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> {
        sentMessages.add(sessionId to parts)
        return sendMessageResult
    }

    override suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> {
        repliedQuestions.add(questionId to answer)
        return replyQuestionResult
    }

    override suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ): Result<Unit> {
        promptAsyncCalls.add(sessionId to parts)
        return promptAsyncResult
    }

    override suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit> =
        revertResult

    override suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit> =
        unrevertResult

    override suspend fun respondPermission(
        serverId: String,
        sessionId: String,
        permissionId: String,
        reply: String,
        directory: String?
    ): Result<Boolean> = respondPermissionResult

    // ============ 待处理查询 ============

    override suspend fun listPendingPermissions(serverId: String, directory: String?): Result<List<PermissionState>> =
        listPendingPermissionsResult

    override suspend fun listPendingQuestions(serverId: String, directory: String?): Result<List<QuestionState>> =
        listPendingQuestionsResult

    override suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Boolean> = replyToQuestionResult

    override suspend fun rejectQuestion(serverId: String, requestId: String, directory: String?): Result<Boolean> =
        rejectQuestionResult

    // ============ Undo/Redo ============

    // ============ 命令执行 ============

    override suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?
    ): Result<Boolean> {
        executeCommandCalls.add(mapOf(
            "serverId" to serverId,
            "sessionId" to sessionId,
            "command" to command,
            "arguments" to arguments
        ))
        return executeCommandResult
    }

    override suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String?,
        modelId: String?,
        directory: String?
    ): Result<Boolean> = runShellCommandResult

    // ============ 后台活动（V2） ============

    override suspend fun backgroundSession(serverId: String, sessionId: String): Result<Boolean> =
        Result.success(true)

    override suspend fun listActiveSessions(serverId: String): Result<Map<String, ActiveSessionInfo>> =
        Result.success(emptyMap())

    override suspend fun listShells(serverId: String, directory: String?): Result<List<ShellJob>> =
        Result.success(emptyList())

    override suspend fun getShellOutput(
        serverId: String,
        shellId: String,
        cursor: Long?,
        limit: Int?,
        directory: String?
    ): Result<ShellOutput?> = Result.success(null)

    override suspend fun removeShell(serverId: String, shellId: String, directory: String?): Result<Boolean> =
        Result.success(true)

    // ============ UI 状态 ============

    override fun getToolExpandedStates(): Map<String, Boolean> = toolExpandedStates.toMap()

    override fun setToolExpanded(toolId: String, expanded: Boolean) {
        toolExpandedStates[toolId] = expanded
    }

    // ============ 权限自动批准 ============

    override suspend fun addPermissionAutoApproveRule(rule: AutoApproveRule) {
        autoApproveRules.add(rule)
    }

    // ============ 写操作（状态更新） ============

    override fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        strategy: MergeStrategy,
    ) {
        when (strategy) {
            MergeStrategy.SSE_PRIORITY, MergeStrategy.REST_AUTHORITY -> {
                messagesStore[sessionId] = messages.toMutableList()
            }
            MergeStrategy.APPEND_ONLY -> {
                messagesStore.getOrPut(sessionId) { mutableListOf() }.addAll(messages)
            }
        }
    }

    override fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesStore[sessionId] = messages.toMutableList()
    }

    override fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesStore.getOrPut(sessionId) { mutableListOf() }.addAll(messages)
    }

    override fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesStore[sessionId] = messages.toMutableList()
    }

    override fun clearRevert(sessionId: String) {
        revertStore.remove(sessionId)
    }

    override fun setRevert(sessionId: String, messageId: String) {
        revertStore[sessionId] = messageId
    }

    override fun removePermission(permissionId: String) {
        permissionsStore.values.forEach { list -> list.removeAll { it.id == permissionId } }
        permissionsState.value = permissionsState.value.filterNot { it.id == permissionId }
    }

    override fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) {
        permissionsStore[sessionId] = permissions.toMutableList()
        // 必须在 allPermissionsMapState 上发射，使 ViewModel 的 combine flow 重新触发
        allPermissionsMapState.value = permissionsStore.mapValues { it.value.toList() }
    }

    override fun removeQuestion(questionId: String) {
        questionsStore.values.forEach { list -> list.removeAll { it.id == questionId } }
        questionsState.value = questionsState.value.filterNot { it.id == questionId }
    }

    override fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) {
        questionsStore[sessionId] = questions.toMutableList()
        // 必须在 allQuestionsMapState 上发射，使 ViewModel 的 combine flow 重新触发
        allQuestionsMapState.value = questionsStore.mapValues { it.value.toList() }
    }

    override fun getPermissionsWithChildren(
        sessionId: String,
        sessions: List<Session>
    ): List<SseEvent.PermissionAsked> {
        return permissionsStore[sessionId] ?: emptyList()
    }

    override fun getQuestionsWithChildren(
        sessionId: String,
        sessions: List<Session>
    ): List<SseEvent.QuestionAsked> {
        return questionsStore[sessionId] ?: emptyList()
    }

    // ============ 原始状态读取 ============

    override fun getPermissionsSnapshot(): Map<String, List<SseEvent.PermissionAsked>> =
        permissionsStore.mapValues { it.value.toList() }

    override fun getQuestionsSnapshot(): Map<String, List<SseEvent.QuestionAsked>> =
        questionsStore.mapValues { it.value.toList() }

    override fun getSessionsSnapshot(): List<Session> = sessionsSnapshot

    // ============ 测试辅助 ============

    /** 设置 sessions 快照（用于需要 getSessionsSnapshot 返回数据的测试）。 */
    fun setSessionsSnapshot(sessions: List<Session>) {
        sessionsSnapshot = sessions
    }
}
