package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageTerminalUseCase
import dev.leonardo.ocbeacon.domain.usecase.ShareExportUseCase
import dev.leonardo.ocbeacon.domain.usecase.UndoRedoUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SessionActionsDelegate"

/**
 * 管理此前内联在 [ChatViewModel] 中的 24 个无状态 REST 操作。
 *
 * 在 Phase 3 Task 6（G 集群）中提取。
 *
 * 这些方法不持有私有 [kotlinx.coroutines.flow.StateFlow] —— 它们通过注入的
 * provider/回调读取其他 delegate 的状态，并委托给 UseCase/Repository。
 * 跨 delegate 协调器（[ChatViewModel.sendParts]、[ChatViewModel.revertMessage]、
 * [ChatViewModel.abortSession]）留在 [ChatViewModel] 中，因为它们写入多个
 * delegate 的私有状态并编排复杂流程（发送 → 观察 → 错误 →
 * 恢复草稿，暂停 → revert → 重连 SSE）。
 *
 * [abortSession] 的 REST 部分（abort + markIdle）在此处；[ChatViewModel] 调用它
 * 然后处理 SSE job 的取消/重启。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的运行时
 * 上下文（ViewModel 的协程作用域、跨 delegate provider/回调），
 * Hilt 无法提供这些。ChatViewModel 直接构造它并将每个成员作为门面重新暴露，
 * 因此 UI 文件无需改动。
 */
internal class SessionActionsDelegate(
    private val shareExportUseCase: ShareExportUseCase,
    private val undoRedoUseCase: UndoRedoUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val sessionRepository: SessionRepository,
    private val chatRepository: ChatRepository,
    private val sessionStateService: SessionStateRepository,
    private val serverId: String,
    private val scope: CoroutineScope,
    private val sessionIdProvider: () -> String,
    private val sessionDirectoryProvider: () -> String?,
    private val modelConfigProvider: () -> ModelConfigState,
    private val messageListProvider: () -> List<ChatMessage>,
    private val ensureSession: suspend () -> String,
    private val loadSessionInfo: suspend () -> Unit,
    private val awaitSessionLoaded: suspend () -> Unit,
    private val refreshMessages: suspend () -> Unit,
    private val loadPendingQuestions: suspend () -> Unit,
    private val loadPendingPermissions: suspend () -> Unit,
    private val restoreRevertedDraft: (RevertedDraftPayload) -> Unit,
) {
    private val sessionId: String get() = sessionIdProvider()

    // ============ 刷新跟踪 ============
    private var lastRefreshTimeMs: Long = 0L

    // ============ 刷新 / 同步 ============

    /**
     * 刷新会话数据 —— 从 REST 重新加载消息和会话状态。
     */
    fun refreshSession() {
        scope.launch {
            refreshAndSync()
        }
    }

    /**
     * 仅在距上次刷新足够时间后才刷新会话。
     * 从 ON_RESUME 调用 —— 避免短暂应用切换期间的不必要 REST 调用。
     *
     * 仅同步会话状态并通过 REST 刷新消息。
     * 不重启 sseJob 以避免滚动位置重置和数据闪烁。
     */
    fun refreshIfNeeded() {
        val elapsed = System.currentTimeMillis() - lastRefreshTimeMs
        if (elapsed >= REFRESH_COOLDOWN_MS) {
            refreshSession()
        }
    }

    /**
     * 查询 OpenCode 服务器的实际会话状态，纠正
     * 因丢失 SSE 事件导致的 UI 状态偏移。
     *
     * 在进入会话和从后台恢复时触发。
     * 委托给 [SessionStateService.requestValidation] —— FSM 的
     * forceComplete 机制在 REST 确认 Idle 时处理未完成消息修复。
     */
    fun syncSessionStatus() {
        scope.launch {
            if (sessionId.isNotBlank()) {
                awaitSessionLoaded()
                sessionStateService.requestValidation(sessionId)
            }
        }
    }

    /**
     * 组合刷新 + 同步 —— 在单个协程中运行，避免
     * 并行 REST 响应之间的状态冲突。
     *
     * 状态验证委托给 [SessionStateService.requestValidation]；
     * FSM 的 forceComplete 机制处理未完成消息修复。
     */
    private suspend fun refreshAndSync() {
        loadSessionInfo()
        refreshMessages()
        if (sessionId.isNotBlank()) {
            awaitSessionLoaded()
            sessionStateService.requestValidation(sessionId)
        }
        loadPendingQuestions()
        loadPendingPermissions()
        lastRefreshTimeMs = System.currentTimeMillis()
    }

    // ============ 权限 / 问题 ============

    /**
     * 回复权限请求。
     * @param requestId 权限请求 ID
     * @param reply 取值之一："once"、"always"、"reject"
     */
    fun replyToPermission(requestId: String, reply: String) {
        scope.launch {
            val logMsg = "[Permission] replyToPermission: id=$requestId reply=$reply dir=${sessionDirectoryProvider()}"
            Log.i(TAG, logMsg)
            try {
                val success = managePermissionUseCase.replyToPermission(
                    serverId = serverId,
                    requestId = requestId,
                    reply = reply,
                    directory = sessionDirectoryProvider()
                )
                val resultMsg = "[Permission] replyToPermission result: id=$requestId success=$success"
                Log.i(TAG, resultMsg)
                if (success) {
                    chatRepository.removePermission(requestId)
                } else {
                    val warnMsg = "[Permission] API returned failure for $requestId, removing card as fallback (likely already replied)"
                    Log.w(TAG, warnMsg)
                    chatRepository.removePermission(requestId)
                }
            } catch (e: Exception) {
                val errMsg = "[Permission] Exception replying to $requestId: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errMsg, e)
                chatRepository.removePermission(requestId)
            }
        }
    }

    fun savePermissionRule(event: SseEvent.PermissionAsked, directory: String) {
        scope.launch {
            val rule = AutoApproveRule(
                toolName = event.permission,
                sessionId = null,
                directoryPattern = directory
            )
            chatRepository.addPermissionAutoApproveRule(rule)
        }
    }

    /**
     * 回复问题请求。
     * @param requestId 问题请求 ID
     * @param answers 每个问题的答案（每个问题的已选标签列表）
     */
    fun replyToQuestion(requestId: String, answers: List<List<String>>) {
        scope.launch {
            val logMsg = "[Question] replyToQuestion: id=$requestId answers=$answers dir=${sessionDirectoryProvider()}"
            Log.i(TAG, logMsg)
            try {
                val success = managePermissionUseCase.replyToQuestion(
                    serverId = serverId,
                    requestId = requestId,
                    answers = answers,
                    directory = sessionDirectoryProvider()
                )
                val resultMsg = "[Question] replyToQuestion result: id=$requestId success=$success"
                Log.i(TAG, resultMsg)
                chatRepository.removeQuestion(requestId)
            } catch (e: Exception) {
                val errMsg = "[Question] Exception replying to $requestId: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errMsg, e)
                chatRepository.removeQuestion(requestId)
            }
        }
    }

    /**
     * 拒绝问题请求。
     */
    fun rejectQuestion(requestId: String) {
        scope.launch {
            val logMsg = "[Question] rejectQuestion: id=$requestId dir=${sessionDirectoryProvider()}"
            Log.i(TAG, logMsg)
            try {
                val success = managePermissionUseCase.rejectQuestion(
                    serverId = serverId,
                    requestId = requestId,
                    directory = sessionDirectoryProvider()
                )
                val resultMsg = "[Question] rejectQuestion result: id=$requestId success=$success"
                Log.i(TAG, resultMsg)
                chatRepository.removeQuestion(requestId)
            } catch (e: Exception) {
                val errMsg = "[Question] Exception rejecting $requestId: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errMsg, e)
                chatRepository.removeQuestion(requestId)
            }
        }
    }

    // ============ 分享 / 导出 / 压缩 ============

    /** 分享当前会话。返回分享 URL 或失败时返回 null。 */
    fun shareSession(onResult: (String?) -> Unit) {
        scope.launch {
            try {
                val session = shareExportUseCase.shareSession(serverId, sessionId)
                val url = session.share?.url
                if (BuildConfig.DEBUG) Log.d(TAG, "Shared session $sessionId: $url")
                onResult(url)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share session", e)
                onResult(null)
            }
        }
    }

    fun unshareSession(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                shareExportUseCase.unshareSession(serverId, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unshared session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unshare session", e)
                onResult(false)
            }
        }
    }

    /** 压缩（摘要）当前会话。 */
    fun compactSession(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val config = modelConfigProvider()
                val providerId = config.selectedProviderId
                val modelId = config.selectedModelId
                if (providerId == null || modelId == null) {
                    Log.e(TAG, "Cannot compact: no model selected")
                    onResult(false)
                    return@launch
                }
                shareExportUseCase.compactSession(serverId, sessionId, providerId, modelId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Compacted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compact session", e)
                onResult(false)
            }
        }
    }

    /**
     * 将会话以 JSON 直接导出到文件 URI。
     * 流式传输 API 响应直接到输出流，避免大会话
     *（消息可达 80+ MB）时的 OOM。
     * 显示带下载进度的通知。
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "opencode_export"
            val notificationId = 9999

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    context.getString(R.string.menu_export_session),
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_export_progress)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.menu_export_session))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)

            try {
                Log.d(TAG, "exportSession: streaming to $uri")
                notificationManager.notify(notificationId, builder.build())

                var lastNotifyTime = 0L
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    shareExportUseCase.exportSessionToStream(serverId, sessionId, outputStream) { bytesWritten ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTime > 500) {
                            lastNotifyTime = now
                            val mb = String.format("%.1f MB", bytesWritten / 1_000_000.0)
                            builder.setContentText(mb)
                            notificationManager.notify(notificationId, builder.build())
                        }
                    }
                }

                Log.d(TAG, "exportSession: done")
                notificationManager.cancel(notificationId)
                withContext(Dispatchers.Main) {
                    onResult(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export session", e)
                notificationManager.cancel(notificationId)
                withContext(Dispatchers.Main) {
                    onResult(false)
                }
            }
        }
    }

    // ============ 撤销 / 重做 ============

    /** 撤销会话中最后一条用户消息，将其文本恢复到输入框。 */
    fun undoMessage(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val messages = messageListProvider()
                val lastUser = messages.firstOrNull { it.isUser }
                if (lastUser == null) {
                    onResult(false)
                    return@launch
                }
                undoRedoUseCase.revertSession(serverId, sessionId, lastUser.message.id)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message ${lastUser.message.id}")
                restoreRevertedDraft(extractRevertedDraft(lastUser))
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert session", e)
                onResult(false)
            }
        }
    }

    /** Redo 最后一次撤销的消息。 */
    fun redoMessage(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                undoRedoUseCase.unrevertSession(serverId, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unreverted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unrevert session", e)
                onResult(false)
            }
        }
    }

    /**
     * 从 [ChatMessage] 中提取文本和图片 URI 用于草稿恢复。
     * 纯函数 —— 也被 [ChatViewModel.revertMessage] 协调器使用。
     */
    fun extractRevertedDraft(message: ChatMessage): RevertedDraftPayload {
        val revertedText = message.parts
            .filterIsInstance<Part.Text>()
            .joinToString("\n") { it.text }

        val imageUris = message.parts
            .filterIsInstance<Part.File>()
            .mapNotNull { part ->
                val mime = part.mime.lowercase()
                if (mime.startsWith("image/") && !part.url.isNullOrBlank()) part.url else null
            }

        return RevertedDraftPayload(
            text = revertedText,
            attachmentUris = imageUris,
        )
    }

    // ============ 消息操作 ============

    /** 从当前会话删除一条消息。 */
    fun deleteMessage(messageId: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val success = manageSessionUseCase.deleteMessage(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Deleted message $messageId: success=$success")
                onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete message $messageId", e)
                onResult(false)
            }
        }
    }

    /** 通过索引从消息中删除特定 part。 */
    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val success = manageSessionUseCase.deleteMessagePart(serverId, sessionId, messageId, partIndex)
                if (BuildConfig.DEBUG) Log.d(TAG, "Deleted part $partIndex from message $messageId: success=$success")
                onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete part $partIndex from message $messageId", e)
                onResult(false)
            }
        }
    }

    // ============ 会话操作 ============

    /**
     * 当收到 SessionUpdated SSE 事件时调用。
     * 刷新消息列表以获取 revert/unrevert 变更。
     */
    fun onSessionUpdated(session: Session) {
        if (session.id != sessionId) return
        scope.launch {
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sessionId, 100)
                chatRepository.replaceMessages(sessionId, messages)
                if (BuildConfig.DEBUG) Log.d(TAG, "Refreshed messages after session update")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh messages after session update", e)
            }
        }
    }

    /** Fork 当前会话。返回新会话或 null。 */
    fun forkSession(onResult: (Session?) -> Unit) {
        scope.launch {
            try {
                val session = manageSessionUseCase.forkSession(serverId, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Forked session $sessionId -> ${session.id}")
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }

    /** 重命名当前会话。 */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                manageSessionUseCase.renameSession(serverId, sessionId, title)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to $title")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                onResult(false)
            }
        }
    }

    /**
     * Abort REST 调用 —— 在服务器上取消会话并通过
     * FSM（ClientAbort → Idle + forceComplete 消息）标记为 idle。
     * SSE job 的取消/重启由 [ChatViewModel.abortSession] 协调器处理。
     */
    suspend fun abortSession() {
        sessionRepository.abort(serverId, sessionId, sessionDirectoryProvider())
        if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
        sessionStateService.onClientAbort(sessionId)
    }

    // ============ 命令 ============

    /** 执行服务端命令（如 /init、/review、MCP 命令）。 */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val currentSessionId = ensureSession()
                if (sessionDirectoryProvider().isNullOrBlank()) {
                    loadSessionInfo()
                }

                val normalizedCommand = command.removePrefix("/").trim()
                val effectiveDirectory = sessionDirectoryProvider()
                    ?: chatRepository.getSessionsSnapshot()
                        .firstOrNull { it.id == currentSessionId }
                        ?.directory
                        ?.takeIf { it.isNotBlank() }
                val effectiveArguments = if (
                    normalizedCommand.equals("init", ignoreCase = true) && arguments.isBlank()
                ) {
                    ""
                } else {
                    arguments
                }

                val ok = manageTerminalUseCase.executeCommand(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    command = normalizedCommand,
                    arguments = effectiveArguments,
                    directory = effectiveDirectory,
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Executed command /$normalizedCommand in session $currentSessionId: $ok (directory=$effectiveDirectory, arguments=$effectiveArguments)"
                    )
                }
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command /$command", e)
                onResult(false)
            }
        }
    }

    /** 在当前会话中执行 shell 命令。 */
    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            onResult(false)
            return
        }
        scope.launch {
            try {
                val modelCfg = modelConfigProvider()
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId,
                        modelId = modelCfg.selectedModelId
                    )
                } else null
                val ok = manageTerminalUseCase.runShellCommand(
                    serverId = serverId,
                    sessionId = sessionId,
                    command = trimmed,
                    agent = modelCfg.selectedAgent,
                    model = model,
                    directory = sessionDirectoryProvider()
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Executed shell command in session $sessionId: $ok")
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute shell command", e)
                onResult(false)
            }
        }
    }

    // ============ 辅助方法 ============

    /** 获取最后一条 assistant 消息文本以供复制。 */
    fun getLastAssistantText(): String? {
        val msgs = messageListProvider()
        val last = msgs.firstOrNull { it.isAssistant } ?: return null
        return last.parts
            .filterIsInstance<Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { null }
    }

    companion object {
        const val REFRESH_COOLDOWN_MS = 5_000L
    }
}
