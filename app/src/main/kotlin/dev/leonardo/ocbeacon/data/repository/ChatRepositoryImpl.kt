package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.BuildConfig
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
import dev.leonardo.ocbeacon.domain.model.ActiveSessionInfo
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
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
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

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
    private val shellApi: dev.leonardo.ocbeacon.data.api.shell.ShellApi,
    private val providerApi: ProviderApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerDataStore,
    private val permissionAutoApprover: PermissionAutoApprover,
    private val messageStore: MessageCacheRepository,
) : ChatRepository {

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
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("ChatRepository", "[seed] session=$sessionId: ${cached.size} cached messages -> memory hot view")
                    }
                    // #76 修复（2026-08-11）：observeMessages 返回降序
                    //（ORDER BY created DESC, id DESC），而 mergeSortedMessages
                    // 两路归并前提是升序——降序输入会导致归并错乱/消息丢失
                    //（synthetic 卡片实测：seed 14 条 → REST refresh 后 UI 仅 12 条）。
                    // 沿用现有合并路径写入内存热视图（APPEND_ONLY：不去重已存在，幂等）
                    // #103（M-5）：排序+合并移出主线程（原在 flow 收集线程=Main）
                    withContext(Dispatchers.Default) {
                        val cachedAsc = cached.sortedBy { it.info.time.created }
                        // #171：缓存种子走纯缓存入口——DB 回读载荷不喂红点水位线
                        //（completed 可能携带 markSessionIdle 的客户端终结戳）
                        eventDispatcher.seedCachedMessages(sessionId, cachedAsc)
                    }
                } else if (BuildConfig.DEBUG) {
                    AppLogger.d("ChatRepository", "[seed] session=$sessionId: no cache, waiting for REST")
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
            .distinctUntilChanged()

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
            .distinctUntilChanged()

    override fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        eventDispatcher.questions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toQuestionState() }
        }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getQuestionsFlow", e)
                emit(emptyList())
            }
            .distinctUntilChanged()

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
            .distinctUntilChanged()

    override fun getStepProgress(serverId: String): Flow<StepProgressInfo?> =
        eventDispatcher.stepProgress.map { it[serverId]?.toDomain() }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getStepProgress", e)
                emit(null)
            }
            .distinctUntilChanged()

    override fun getCompactionState(serverId: String): Flow<CompactionStateInfo?> =
        eventDispatcher.compactionState.map { it[serverId]?.toDomain() }
            .catch { e ->
                AppLogger.e("ChatRepository", "Error in getCompactionState", e)
                emit(null)
            }
            .distinctUntilChanged()

    // ============ 网络操作 ============

    override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> = runCatchingCancellable {
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

    override suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> = runCatchingCancellable {
        val sessionId = findSessionForQuestion(questionId)
            ?: throw IllegalStateException("Session not found for question $questionId")
        val conn = resolveConnectionForSession(sessionId)
        // #130：V2 form reply 需要领域问题（key/value 映射）——从 pending 状态查找。
        val question = eventDispatcher.questions.value.values.flatten()
            .firstOrNull { it.id == questionId }
        messageApi.replyToQuestion(conn, questionId, listOf(listOf(answer)), question = question)
    }

    override suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        val admission = messageApi.promptAsync(
            conn, sessionId, parts.map { it.toData() }, model?.toData(), agent, variant, directory
        )
        // 2026-08-14 根治（用户消息"发送后无气泡"系统性修复）：
        // V2 prompt 响应体即 Inbox 条目（含消息 id）——立即本地播种用户消息，
        // 不等 SSE session.inbox.enqueued 回显。SSE 到达时同 id 幂等合并
        // （handleMessageUpdated idx>=0 替换分支）；SSE 丢失/延迟/服务器
        // 版本事件名差异均不再导致用户消息气泡缺失。
        // V1（prompt_async 204 无响应体）→ admission=null → 依赖 SSE 回显。
        val text = parts.firstOrNull { it.type == "text" }?.text
        if (admission != null && admission.id.isNotBlank()) {
            if (BuildConfig.DEBUG) {
                AppLogger.d("ChatRepository", "[send-seed] user message ${admission.id} (SSE 回显前本地播种)")
            }
            eventDispatcher.processEvent(
                SseEvent.MessageUpdated(
                    Message.User(
                        id = admission.id,
                        sessionId = admission.sessionId,
                        time = TimeInfo(System.currentTimeMillis()),
                        summary = Message.User.UserSummary(body = admission.text ?: text)
                    )
                ),
                serverId
            )
        }
    }

    override suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.revertSession(conn, sessionId, messageId)
    }

    override suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.unrevertSession(conn, sessionId)
    }

    override suspend fun respondPermission(
        serverId: String,
        sessionId: String,
        permissionId: String,
        reply: String,
        directory: String?
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.replyToPermission(conn, sessionId, permissionId, reply, directory = directory)
    }

    // ============ 待处理查询 ============

    override suspend fun listPendingPermissions(serverId: String, directory: String?): Result<List<PermissionState>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.listPendingPermissions(conn, directory).map { it.toDomainPermissionState() }
    }

    override suspend fun listPendingQuestions(serverId: String, directory: String?): Result<List<QuestionState>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        messageApi.listPendingQuestions(conn, directory).map { it.toDomainQuestionState() }
    }

    override suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        // #130：V2 form reply 需要领域问题（sessionId + key/value 映射）。
        // V1 分支忽略该参数；找不到时 V2 返回 false（调用方移除卡片兜底）。
        val question = eventDispatcher.questions.value.values.flatten()
            .firstOrNull { it.id == requestId }
        messageApi.replyToQuestion(conn, requestId, answers, directory, question)
    }

    override suspend fun rejectQuestion(
        serverId: String,
        requestId: String,
        directory: String?
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        // #130：V2 form cancel 需要 sessionID（路径参数）。
        val sessionId = eventDispatcher.questions.value.entries
            .firstOrNull { (_, qs) -> qs.any { it.id == requestId } }?.key
        messageApi.rejectQuestion(conn, requestId, directory, sessionId)
    }

    // ============ 命令执行 ============

    override suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?,
        agent: String?,
        model: String?,
        variant: String?,
        parts: List<Map<String, String>>?
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.executeCommand(conn, sessionId, command, arguments, directory, agent, model, variant, parts)
    }

    override suspend fun setPermissionPreset(
        serverId: String,
        sessionId: String,
        preset: String,
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.setPermissionPreset(conn, sessionId, preset)
    }

    override suspend fun listAgentPresets(serverId: String): Result<List<AgentPreset>> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.listAgentPresets(conn)
    }

    override suspend fun selectAgentPreset(
        serverId: String,
        sessionId: String,
        presetId: String,
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.selectAgentPreset(conn, sessionId, presetId)
    }

    override suspend fun updateQueueItem(
        serverId: String,
        sessionId: String,
        itemId: String,
        action: dev.leonardo.ocbeacon.domain.model.QueueActionKind,
        editText: String?,
    ): dev.leonardo.ocbeacon.domain.model.QueueMutationResult {
        val conn = resolveConnection(serverId)
        return sessionApi.updateQueue(conn, sessionId, itemId, action, editText)
    }


    // ============ DSH goal mutation（backlog #286） ============

    override suspend fun createGoal(
        serverId: String,
        sessionId: String,
        objective: String,
        maxGoalRounds: Long?,
    ): Result<DshGoalRef?> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.goalCreate(conn, sessionId, objective, maxGoalRounds)
    }

    override suspend fun editGoal(
        serverId: String,
        sessionId: String,
        ref: DshGoalRef,
        objective: String?,
        maxGoalRounds: Long?,
    ): Result<DshGoalRef?> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        sessionApi.goalEdit(conn, sessionId, ref, objective, maxGoalRounds)
    }

    override suspend fun pauseGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<DshGoalRef?> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            sessionApi.goalPause(conn, sessionId, ref)
        }

    override suspend fun resumeGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<DshGoalRef?> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            sessionApi.goalResume(conn, sessionId, ref)
        }

    override suspend fun completeGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<DshGoalRef?> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            sessionApi.goalComplete(conn, sessionId, ref)
        }

    override suspend fun clearGoal(serverId: String, sessionId: String, ref: DshGoalRef): Result<Boolean> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            sessionApi.goalClear(conn, sessionId, ref)
        }

    override suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String?,
        modelId: String?,
        directory: String?
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        val model = if (providerId != null && modelId != null) {
            DataModelSelection(providerId = providerId, modelId = modelId)
        } else null
        terminalApi.runShellCommand(conn, sessionId, command, agent, model, directory)
    }

    override suspend fun backgroundSession(serverId: String, sessionId: String): Result<Boolean> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            sessionApi.backgroundSession(conn, sessionId)
        }

    override suspend fun listActiveSessions(serverId: String): Result<Map<String, ActiveSessionInfo>> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            sessionApi.activeSessions(conn)
        }

    override suspend fun listShells(serverId: String, directory: String?): Result<List<ShellJob>> =
        runCatchingCancellable {
            val conn = resolveConnection(serverId)
            shellApi.listShells(conn, directory)
        }

    override suspend fun getShellOutput(
        serverId: String,
        shellId: String,
        cursor: Long?,
        limit: Int?,
        directory: String?
    ): Result<ShellOutput?> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        shellApi.getShellOutput(conn, shellId, cursor, limit, directory)
    }

    override suspend fun removeShell(
        serverId: String,
        shellId: String,
        directory: String?
    ): Result<Boolean> = runCatchingCancellable {
        val conn = resolveConnection(serverId)
        shellApi.removeShell(conn, shellId, directory)
    }

    // ============ 私有辅助方法 ============

    private companion object {
    }

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        // #276：from(config) 单点沿传 serverType（DSH 三分路由依据）
        return ServerConnection.from(config)
    }

    private suspend fun resolveConnectionForSession(sessionId: String): ServerConnection {
        val serverId = eventDispatcher.serverSessions.value.entries
            .find { sessionId in it.value }?.key
            ?: throw IllegalStateException("No server found for session $sessionId")
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        // #276：from(config) 单点沿传 serverType（DSH 三分路由依据）
        return ServerConnection.from(config)
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
                    QuestionState.Option(label = o.label, description = o.description, value = o.value)
                },
                key = q.key
            )
        },
        tool = tool
    )

    private fun dev.leonardo.ocbeacon.data.dto.response.PermissionRequest.toDomainPermissionState() = PermissionState(
        id = id,
        sessionId = sessionId,
        // 2026-08-16（F6）：V2 REST 条目无 permission/patterns——action/resources 兜底
        permission = permission ?: action ?: "",
        patterns = patterns.ifEmpty { resources },
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
                    QuestionState.Option(label = o.label, description = o.description, value = o.value)
                },
                key = q.key
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
        isActive = isActive, reason = reason,
        deltaText = deltaText, messageId = messageId
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
        eventDispatcher.activeToolProgress.map { map -> map[sessionId]?.map { it.toDomain() } }.distinctUntilChanged()

    override fun getStepProgressForSession(sessionId: String): Flow<StepProgressInfo?> =
        eventDispatcher.stepProgress.map { it[sessionId]?.toDomain() }.distinctUntilChanged()

    override fun getCompactionStateForSession(sessionId: String): Flow<CompactionStateInfo?> =
        eventDispatcher.compactionState.map { it[sessionId]?.toDomain() }.distinctUntilChanged()

    override fun getSessionDiffsForSession(sessionId: String): Flow<List<FileDiff>> =
        eventDispatcher.sessionDiffs.map { it[sessionId] ?: emptyList() }.distinctUntilChanged()

    // ============ 权限自动批准 ============

    override suspend fun addPermissionAutoApproveRule(rule: dev.leonardo.ocbeacon.domain.model.AutoApproveRule) {
        permissionAutoApprover.addRule(rule)
    }
}