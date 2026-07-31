package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
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
 * Fake ChatRepository with 46 methods.
 *
 * Pattern:
 * - Flow methods return public MutableStateFlow fields (tests set .value)
 * - suspend methods return configurable Result fields (defaults = success)
 * - Sync mutation methods record calls + update state
 *
 * Session-agnostic: all flow methods return the same flow regardless of sessionId/serverId.
 */
@Singleton
class FakeChatRepository @Inject constructor() : ChatRepository {

    // ============ Controllable State Flows ============

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

    // Internal backing stores for sync mutations
    private val messagesStore = mutableMapOf<String, MutableList<MessageWithParts>>()
    private val toolExpandedStates = mutableMapOf<String, Boolean>()
    private val permissionsStore = mutableMapOf<String, MutableList<SseEvent.PermissionAsked>>()
    private val questionsStore = mutableMapOf<String, MutableList<SseEvent.QuestionAsked>>()
    private val revertStore = mutableMapOf<String, String>()
    private val autoApproveRules = mutableListOf<AutoApproveRule>()
    private var sessionsSnapshot: List<Session> = emptyList()

    // ============ Configurable suspend Results ============

    var sendMessageResult: Result<Message> = Result.success(
        Message.User(
            id = "msg-default",
            sessionId = "test-session",
            time = dev.leonardo.ocbeacon.domain.model.TimeInfo(created = System.currentTimeMillis())
        )
    )
    var replyPermissionResult: Result<Boolean> = Result.success(true)
    var replyQuestionResult: Result<Boolean> = Result.success(true)
    var promptAsyncResult: Result<Unit> = Result.success(Unit)
    var revertResult: Result<Unit> = Result.success(Unit)
    var unrevertResult: Result<Unit> = Result.success(Unit)
    var respondPermissionResult: Result<Boolean> = Result.success(true)
    var selectModelResult: Result<Unit> = Result.success(Unit)
    var listPendingPermissionsResult: Result<List<PermissionState>> = Result.success(emptyList())
    var listPendingQuestionsResult: Result<List<QuestionState>> = Result.success(emptyList())
    var replyToQuestionResult: Result<Boolean> = Result.success(true)
    var rejectQuestionResult: Result<Boolean> = Result.success(true)
    var undoRedoResult: Result<Unit> = Result.success(Unit)
    var executeCommandResult: Result<Boolean> = Result.success(true)
    var runShellCommandResult: Result<Boolean> = Result.success(true)

    // ============ Call Recording ============

    val sentMessages = mutableListOf<Pair<String, List<Part>>>()
    val promptAsyncCalls = mutableListOf<Pair<String, List<PromptPart>>>()
    val repliedPermissions = mutableListOf<Pair<String, String>>()
    val repliedQuestions = mutableListOf<Pair<String, String>>()
    val undoRedoCalls = mutableListOf<Triple<String, String, String>>()
    val executeCommandCalls = mutableListOf<Map<String, String>>()

    // ============ State Observations ============

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

    // ============ Session-keyed Flow Observations ============

    override fun getActiveToolProgressForSession(sessionId: String): Flow<List<ToolProgressInfo>?> = toolProgressState

    override fun getStepProgressForSession(sessionId: String): Flow<StepProgressInfo?> = stepProgressState

    override fun getCompactionStateForSession(sessionId: String): Flow<CompactionStateInfo?> = compactionState

    override fun getSessionDiffsForSession(sessionId: String): Flow<List<FileDiff>> = sessionDiffsState

    // ============ Network Operations ============

    override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> {
        sentMessages.add(sessionId to parts)
        return sendMessageResult
    }

    override suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean> {
        repliedPermissions.add(permissionId to reply)
        return replyPermissionResult
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
        permissionId: String,
        reply: String,
        directory: String?
    ): Result<Boolean> = respondPermissionResult

    override suspend fun selectModel(serverId: String, providerId: String, modelId: String): Result<Unit> =
        selectModelResult

    // ============ Pending Queries ============

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

    override suspend fun undoRedo(serverId: String, sessionId: String, action: String): Result<Unit> {
        undoRedoCalls.add(Triple(serverId, sessionId, action))
        return undoRedoResult
    }

    // ============ Command Execution ============

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

    // ============ UI State ============

    override fun getToolExpandedStates(): Map<String, Boolean> = toolExpandedStates.toMap()

    override fun setToolExpanded(toolId: String, expanded: Boolean) {
        toolExpandedStates[toolId] = expanded
    }

    // ============ Permission Auto-Approve ============

    override suspend fun addPermissionAutoApproveRule(rule: AutoApproveRule) {
        autoApproveRules.add(rule)
    }

    // ============ Write Operations (State Updates) ============

    override fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesStore[sessionId] = messages.toMutableList()
    }

    override fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesStore.getOrPut(sessionId) { mutableListOf() }.addAll(messages)
    }

    override fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesStore[sessionId] = messages.toMutableList()
    }

    override fun addOptimisticMessage(sessionId: String, message: Message.User, parts: List<Part>) {
        val msgWithParts = MessageWithParts(message, parts)
        messagesStore.getOrPut(sessionId) { mutableListOf() }.add(msgWithParts)
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
        // Must emit on allPermissionsMapState so the ViewModel's combine flow re-triggers
        allPermissionsMapState.value = permissionsStore.mapValues { it.value.toList() }
    }

    override fun removeQuestion(questionId: String) {
        questionsStore.values.forEach { list -> list.removeAll { it.id == questionId } }
        questionsState.value = questionsState.value.filterNot { it.id == questionId }
    }

    override fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) {
        questionsStore[sessionId] = questions.toMutableList()
        // Must emit on allQuestionsMapState so the ViewModel's combine flow re-triggers
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

    // ============ Raw State Reads ============

    override fun getPermissionsSnapshot(): Map<String, List<SseEvent.PermissionAsked>> =
        permissionsStore.mapValues { it.value.toList() }

    override fun getQuestionsSnapshot(): Map<String, List<SseEvent.QuestionAsked>> =
        questionsStore.mapValues { it.value.toList() }

    override fun getSessionsSnapshot(): List<Session> = sessionsSnapshot

    // ============ Test Helper ============

    /** Set the sessions snapshot (for tests that need getSessionsSnapshot to return data). */
    fun setSessionsSnapshot(sessions: List<Session>) {
        sessionsSnapshot = sessions
    }
}
