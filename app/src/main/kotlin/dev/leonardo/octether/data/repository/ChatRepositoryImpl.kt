package dev.leonardo.octether.data.repository

import dev.leonardo.octether.data.api.message.MessageApi
import dev.leonardo.octether.data.api.provider.ProviderApi
import dev.leonardo.octether.data.api.session.SessionApi
import dev.leonardo.octether.data.api.terminal.TerminalApi
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.data.dto.common.ModelSelection as DataModelSelection
import dev.leonardo.octether.data.dto.request.PromptPart as DataPromptPart
import dev.leonardo.octether.data.repository.handler.CompactionStateInfo as DataCompactionStateInfo
import dev.leonardo.octether.data.repository.handler.StepProgressInfo as DataStepProgressInfo
import dev.leonardo.octether.data.repository.handler.ToolProgressInfo as DataToolProgressInfo
import dev.leonardo.octether.domain.model.CompactionStateInfo
import dev.leonardo.octether.domain.model.FileDiff
import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.MessageWithParts
import dev.leonardo.octether.domain.model.ModelSelection
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.PermissionState
import dev.leonardo.octether.domain.model.PromptPart
import dev.leonardo.octether.domain.model.QuestionState
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SseEvent
import dev.leonardo.octether.domain.model.StepProgressInfo
import dev.leonardo.octether.domain.model.TimeInfo
import dev.leonardo.octether.domain.model.ToolProgressInfo
import dev.leonardo.octether.domain.repository.ChatRepository
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ChatRepository].
 * Bridges domain interface with EventDispatcher (state) and domain APIs (network).
 *
 * Phase 3: compiled but not yet wired to UseCases. Phase 4 will migrate
 * ViewModel direct calls to go through this repository.
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val messageApi: MessageApi,
    private val sessionApi: SessionApi,
    private val terminalApi: TerminalApi,
    private val providerApi: ProviderApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerDataStore,
    private val permissionAutoApprover: PermissionAutoApprover
) : ChatRepository {

    private val toolExpandedStates = mutableMapOf<String, Boolean>()

    // ============ State Observations ============

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> =
        eventDispatcher.messages.map { it[sessionId] ?: emptyList() }
            .catch { e ->
                Log.e("ChatRepository", "Error in getMessagesFlow", e)
                emit(emptyList())
            }

    override fun getParts(sessionId: String): Flow<List<Part>> =
        eventDispatcher.parts.map { partsByMessageId ->
            partsByMessageId.values.flatten().filter { it.sessionId == sessionId }
        }
            .catch { e ->
                Log.e("ChatRepository", "Error in getParts", e)
                emit(emptyList())
            }

    override fun getAllPartsMap(): Flow<Map<String, List<Part>>> =
        eventDispatcher.parts

    override fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> =
        eventDispatcher.permissions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toPermissionState() }
        }
            .catch { e ->
                Log.e("ChatRepository", "Error in getPermissionsFlow", e)
                emit(emptyList())
            }

    override fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        eventDispatcher.questions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toQuestionState() }
        }
            .catch { e ->
                Log.e("ChatRepository", "Error in getQuestionsFlow", e)
                emit(emptyList())
            }

    override fun getAllQuestionsFlow(): Flow<Map<String, List<SseEvent.QuestionAsked>>> =
        eventDispatcher.questions

    override fun getAllPermissionsFlow(): Flow<Map<String, List<SseEvent.PermissionAsked>>> =
        eventDispatcher.permissions

    // ============ EventDispatcher Flow Exposure ============

    override fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?> =
        eventDispatcher.activeToolProgress.map { list -> list[serverId]?.map { it.toDomain() } }
            .catch { e ->
                Log.e("ChatRepository", "Error in getActiveToolProgress", e)
                emit(null)
            }

    override fun getStepProgress(serverId: String): Flow<StepProgressInfo?> =
        eventDispatcher.stepProgress.map { it[serverId]?.toDomain() }
            .catch { e ->
                Log.e("ChatRepository", "Error in getStepProgress", e)
                emit(null)
            }

    override fun getCompactionState(serverId: String): Flow<CompactionStateInfo?> =
        eventDispatcher.compactionState.map { it[serverId]?.toDomain() }
            .catch { e ->
                Log.e("ChatRepository", "Error in getCompactionState", e)
                emit(null)
            }

    // ============ Network Operations ============

    override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> = runCatching {
        val conn = resolveConnectionForSession(sessionId)
        val promptParts = parts.map { it.toDataPromptPart() }
        messageApi.promptAsync(conn, sessionId, promptParts)
        // The actual message arrives via SSE — return a lightweight placeholder.
        // Callers should observe [getMessagesFlow] for the real Message.
        Message.User(
            id = "",
            sessionId = sessionId,
            time = TimeInfo(System.currentTimeMillis())
        )
    }

    override suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean> = runCatching {
        val sessionId = findSessionForPermission(permissionId)
            ?: throw IllegalStateException("Session not found for permission $permissionId")
        val conn = resolveConnectionForSession(sessionId)
        messageApi.replyToPermission(conn, permissionId, reply)
    }

    override suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> = runCatching {
        val sessionId = findSessionForQuestion(questionId)
            ?: throw IllegalStateException("Session not found for question $questionId")
        val conn = resolveConnectionForSession(sessionId)
        messageApi.replyToQuestion(conn, questionId, listOf(listOf(answer)))
    }

    override suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.promptAsync(conn, sessionId, parts.map { it.toData() }, model?.toData(), agent, variant, directory)
    }

    override suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.revertSession(conn, sessionId, messageId)
    }

    override suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.unrevertSession(conn, sessionId)
    }

    override suspend fun respondPermission(
        serverId: String,
        permissionId: String,
        reply: String,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.replyToPermission(conn, permissionId, reply, directory = directory)
    }

    override suspend fun selectModel(serverId: String, providerId: String, modelId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        // TODO: Phase 4 — verify if there is a dedicated updateModel endpoint
        // For now, use config patch as fallback
        providerApi.updateConfig(conn, dev.leonardo.octether.data.dto.request.ServerConfigPatch())
    }

    // ============ Pending Queries ============

    override suspend fun listPendingPermissions(serverId: String, directory: String?): Result<List<PermissionState>> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.listPendingPermissions(conn, directory).map { it.toDomainPermissionState() }
    }

    override suspend fun listPendingQuestions(serverId: String, directory: String?): Result<List<QuestionState>> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.listPendingQuestions(conn, directory).map { it.toDomainQuestionState() }
    }

    override suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.replyToQuestion(conn, requestId, answers, directory)
    }

    override suspend fun rejectQuestion(
        serverId: String,
        requestId: String,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        messageApi.rejectQuestion(conn, requestId, directory)
    }

    // ============ Undo/Redo ============

    override suspend fun undoRedo(serverId: String, sessionId: String, action: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        // The API uses separate revert/unrevert endpoints, not a unified undoRedo.
        // This method dispatches based on action parameter.
        when (action) {
            "undo" -> {
                // Find last user message for undo — callers should provide messageId directly
                // For the unified undoRedo method, revert without specific messageId
                // is not supported by the API. Use revertSession with a messageId instead.
                throw UnsupportedOperationException("Use revertSession(serverId, sessionId, messageId) for undo")
            }
            "redo" -> {
                sessionApi.unrevertSession(conn, sessionId)
            }
            else -> throw IllegalArgumentException("Invalid action: $action. Must be 'undo' or 'redo'")
        }
    }

    // ============ Command Execution ============

    override suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        sessionApi.executeCommand(conn, sessionId, command, arguments, directory)
    }

    override suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String?,
        modelId: String?,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        val model = if (providerId != null && modelId != null) {
            DataModelSelection(providerId = providerId, modelId = modelId)
        } else null
        terminalApi.runShellCommand(conn, sessionId, command, agent, model, directory)
    }

    override fun getToolExpandedStates(): Map<String, Boolean> = toolExpandedStates

    override fun setToolExpanded(toolId: String, expanded: Boolean) {
        toolExpandedStates[toolId] = expanded
    }

    // ============ Private Helpers ============

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }

    private suspend fun resolveConnectionForSession(sessionId: String): ServerConnection {
        val serverId = eventDispatcher.serverSessions.value.entries
            .find { sessionId in it.value }?.key
            ?: throw IllegalStateException("No server found for session $sessionId")
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }

    private fun findSessionForPermission(permissionId: String): String? =
        eventDispatcher.permissions.value.entries
            .firstOrNull { (_, perms) -> perms.any { it.id == permissionId } }
            ?.key

    private fun findSessionForQuestion(questionId: String): String? =
        eventDispatcher.questions.value.entries
            .firstOrNull { (_, qs) -> qs.any { it.id == questionId } }
            ?.key

    // ============ Mappers ============

    private fun SseEvent.PermissionAsked.toPermissionState() = PermissionState(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool
    )

    private fun SseEvent.QuestionAsked.toQuestionState() = QuestionState(
        id = id,
        sessionId = sessionId,
        questions = questions.map { q ->
            QuestionState.Question(
                header = q.header,
                question = q.question,
                multiple = q.multiple,
                custom = q.custom,
                options = q.options.map { o ->
                    QuestionState.Option(label = o.label, description = o.description)
                }
            )
        },
        tool = tool
    )

    private fun dev.leonardo.octether.data.dto.response.PermissionRequest.toDomainPermissionState() = PermissionState(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        // metadata is Map<String, JsonElement> in DTO but Map<String, String> in domain
        metadata = metadata?.mapValues { it.value.toString() },
        always = always?.toString()?.toBoolean() ?: false,
        tool = tool
    )

    private fun dev.leonardo.octether.data.dto.response.QuestionRequest.toDomainQuestionState() = QuestionState(
        id = id,
        sessionId = sessionId,
        questions = questions.map { q ->
            QuestionState.Question(
                header = q.header,
                question = q.question,
                multiple = q.multiple,
                custom = q.custom,
                options = q.options.map { o ->
                    QuestionState.Option(label = o.label, description = o.description)
                }
            )
        },
        tool = tool
    )

    private fun Part.toDataPromptPart(): DataPromptPart = when (this) {
        is Part.Text -> DataPromptPart(type = "text", text = this.text)
        is Part.File -> DataPromptPart(
            type = "file",
            mime = this.mime,
            url = this.url,
            filename = this.filename
        )
        else -> DataPromptPart(type = "text", text = "")
    }

    // ============ Data ↔ Domain Mappers ============

    private fun DataToolProgressInfo.toDomain() = ToolProgressInfo(
        callId = callId, partId = partId, tool = tool,
        status = status, progress = progress, title = title, output = output
    )

    private fun DataStepProgressInfo.toDomain() = StepProgressInfo(
        step = step, agent = agent, model = model
    )

    private fun DataCompactionStateInfo.toDomain() = CompactionStateInfo(
        isActive = isActive, reason = reason
    )

    private fun PromptPart.toData() = DataPromptPart(
        type = type, text = text, path = path,
        mime = mime, url = url, filename = filename
    )

    private fun ModelSelection.toData() = DataModelSelection(
        providerId = providerId, modelId = modelId
    )

    // ============ Write Operations (State Updates) ============

    override fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        eventDispatcher.setMessages(sessionId, messages)
    }

    override fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        eventDispatcher.mergeMessages(sessionId, messages)
    }

    override fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) {
        eventDispatcher.replaceMessages(sessionId, messages)
    }

    override fun addOptimisticMessage(sessionId: String, message: Message.User, parts: List<Part>) {
        eventDispatcher.addOptimisticMessage(sessionId, message, parts)
    }

    override fun clearRevert(sessionId: String) {
        eventDispatcher.clearRevert(sessionId)
    }

    override fun setRevert(sessionId: String, messageId: String) {
        eventDispatcher.setRevert(sessionId, messageId)
    }

    override fun removePermission(permissionId: String) {
        eventDispatcher.removePermission(permissionId)
    }

    override fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) {
        eventDispatcher.setPermissions(sessionId, permissions)
    }

    override fun removeQuestion(questionId: String) {
        eventDispatcher.removeQuestion(questionId)
    }

    override fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) {
        eventDispatcher.setQuestions(sessionId, questions)
    }

    override fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.PermissionAsked> =
        eventDispatcher.getPermissionsWithChildren(sessionId, sessions)

    override fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.QuestionAsked> =
        eventDispatcher.getQuestionsWithChildren(sessionId, sessions)

    // ============ Raw State Reads ============

    override fun getPermissionsSnapshot(): Map<String, List<SseEvent.PermissionAsked>> =
        eventDispatcher.permissions.value

    override fun getQuestionsSnapshot(): Map<String, List<SseEvent.QuestionAsked>> =
        eventDispatcher.questions.value

    override fun getSessionsSnapshot(): List<Session> =
        eventDispatcher.sessions.value

    override fun getActiveToolProgressForSession(sessionId: String): Flow<List<ToolProgressInfo>?> =
        eventDispatcher.activeToolProgress.map { map -> map[sessionId]?.map { it.toDomain() } }

    override fun getStepProgressForSession(sessionId: String): Flow<StepProgressInfo?> =
        eventDispatcher.stepProgress.map { it[sessionId]?.toDomain() }

    override fun getCompactionStateForSession(sessionId: String): Flow<CompactionStateInfo?> =
        eventDispatcher.compactionState.map { it[sessionId]?.toDomain() }

    override fun getSessionDiffsForSession(sessionId: String): Flow<List<FileDiff>> =
        eventDispatcher.sessionDiffs.map { it[sessionId] ?: emptyList() }

    // ============ Permission Auto-Approve ============

    override suspend fun addPermissionAutoApproveRule(rule: dev.leonardo.octether.domain.model.AutoApproveRule) {
        permissionAutoApprover.addRule(rule)
    }
}
