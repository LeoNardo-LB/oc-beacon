package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.dto.common.ModelSelection as DataModelSelection
import dev.leonardo.ocbeacon.data.dto.request.PromptPart as DataPromptPart
import dev.leonardo.ocbeacon.data.repository.handler.CompactionStateInfo as DataCompactionStateInfo
import dev.leonardo.ocbeacon.data.repository.handler.StepProgressInfo as DataStepProgressInfo
import dev.leonardo.ocbeacon.data.repository.handler.ToolProgressInfo as DataToolProgressInfo
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
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ChatRepository] 的实现。
 * 桥接领域接口与 EventDispatcher（状态）和领域 API（网络）。
 *
 * 阶段 3：已编译但尚未接入 UseCase。阶段 4 将把 ViewModel 的
 * 直接调用迁移为通过此 repository。
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val messageApi: MessageApi,
    private val sessionApi: SessionApi,
    private val terminalApi: TerminalApi,
    private val providerApi: ProviderApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerDataStore,
    private val permissionAutoApprover: PermissionAutoApprover,
    private val messageStore: MessageCacheRepository,
) : ChatRepository {

    private val toolExpandedStates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ============ 状态观察 ============

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> = flow {
        // 冷启动种子化：内存热视图为空时从 Room 读最近缓存，
        // 使最后访问会话的消息立即可见（无需等 REST）。
        // 异常降级：Room 查询失败（磁盘满/DB 损坏）不阻断 UI，按空流继续。
        try {
            if (eventDispatcher.messages.value[sessionId].isNullOrEmpty()) {
                val cached = withContext(Dispatchers.IO) {
                    messageStore.observeMessages(sessionId).first()
                }
                if (cached.isNotEmpty()) {
                    // 沿用现有合并路径写入内存热视图（APPEND_ONLY：不去重已存在，幂等）
                    eventDispatcher.upsertMessages(sessionId, cached, MergeStrategy.APPEND_ONLY)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("ChatRepository", "Seed messages from cache failed", e)
        }
        emitAll(
            eventDispatcher.messages.map { it[sessionId] ?: emptyList() }
                .catch { e ->
                    AppLogger.e("ChatRepository", "Error in getMessagesFlow", e)
                    emit(emptyList())
                }
        )
    }

    override fun getParts(sessionId: String): Flow<List<Part>> =
        eventDispatcher.parts.map { partsByMessageId ->
            partsByMessageId.values.flatten().filter { it.sessionId == sessionId }
        }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getParts", e)
                emit(emptyList())
            }

    override fun getAllPartsMap(): Flow<Map<String, List<Part>>> =
        eventDispatcher.parts

    override fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> =
        eventDispatcher.permissions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toPermissionState() }
        }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getPermissionsFlow", e)
                emit(emptyList())
            }

    override fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        eventDispatcher.questions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toQuestionState() }
        }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getQuestionsFlow", e)
                emit(emptyList())
            }

    override fun getAllQuestionsFlow(): Flow<Map<String, List<SseEvent.QuestionAsked>>> =
        eventDispatcher.questions

    override fun getAllPermissionsFlow(): Flow<Map<String, List<SseEvent.PermissionAsked>>> =
        eventDispatcher.permissions

    // ============ EventDispatcher Flow 暴露 ============

    override fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?> =
        eventDispatcher.activeToolProgress.map { list -> list[serverId]?.map { it.toDomain() } }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getActiveToolProgress", e)
                emit(null)
            }

    override fun getStepProgress(serverId: String): Flow<StepProgressInfo?> =
        eventDispatcher.stepProgress.map { it[serverId]?.toDomain() }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getStepProgress", e)
                emit(null)
            }

    override fun getCompactionState(serverId: String): Flow<CompactionStateInfo?> =
        eventDispatcher.compactionState.map { it[serverId]?.toDomain() }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getCompactionState", e)
                emit(null)
            }

    // ============ 网络操作 ============

    override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> = runCatching {
        val conn = resolveConnectionForSession(sessionId)
        val promptParts = parts.map { it.toDataPromptPart() }
        messageApi.promptAsync(conn, sessionId, promptParts)
        // 实际消息通过 SSE 到达——返回一个轻量占位符。
        // 调用方应通过 [getMessagesFlow] 观察真实 Message。
        Message.User(
            id = "",
            sessionId = sessionId,
            time = TimeInfo(System.currentTimeMillis())
        )
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

    // ============ 待处理查询 ============

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

    // ============ 命令执行 ============

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

    // ============ 私有辅助方法 ============

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

    private fun findSessionForQuestion(questionId: String): String? =
        eventDispatcher.questions.value.entries
            .firstOrNull { (_, qs) -> qs.any { it.id == questionId } }
            ?.key

    // ============ 映射器 ============

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

    private fun dev.leonardo.ocbeacon.data.dto.response.PermissionRequest.toDomainPermissionState() = PermissionState(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        // metadata 在 DTO 中是 Map<String, JsonElement>，在领域模型中是 Map<String, String>
        metadata = metadata?.mapValues { it.value.toString() },
        always = always?.toString()?.toBoolean() ?: false,
        tool = tool
    )

    private fun dev.leonardo.ocbeacon.data.dto.response.QuestionRequest.toDomainQuestionState() = QuestionState(
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

    // ============ Data ↔ Domain 映射器 ============

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

    // ============ 写操作（状态更新）============

    override fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        strategy: MergeStrategy,
    ) {
        eventDispatcher.upsertMessages(sessionId, messages, strategy)
    }

    @Deprecated("Use upsertMessages", ReplaceWith("upsertMessages(sessionId, messages, MergeStrategy.SSE_PRIORITY)"))
    override fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        eventDispatcher.setMessages(sessionId, messages)
    }

    @Deprecated("Use upsertMessages", ReplaceWith("upsertMessages(sessionId, messages, MergeStrategy.APPEND_ONLY)"))
    override fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        eventDispatcher.mergeMessages(sessionId, messages)
    }

    @Deprecated("Use upsertMessages", ReplaceWith("upsertMessages(sessionId, messages, MergeStrategy.REST_AUTHORITY)"))
    override fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) {
        eventDispatcher.replaceMessages(sessionId, messages)
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

    // ============ 原始状态读取 ============

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

    // ============ 权限自动批准 ============

    override suspend fun addPermissionAutoApproveRule(rule: dev.leonardo.ocbeacon.domain.model.AutoApproveRule) {
        permissionAutoApprover.addRule(rule)
    }
}
