package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.R
import dev.leonardo.octether.data.repository.SessionStateService
import dev.leonardo.octether.domain.model.AutoApproveRule
import dev.leonardo.octether.domain.model.ModelSelection
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SseEvent
import dev.leonardo.octether.domain.repository.ChatRepository
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.domain.usecase.ManagePermissionUseCase
import dev.leonardo.octether.domain.usecase.ManageSessionUseCase
import dev.leonardo.octether.domain.usecase.ManageTerminalUseCase
import dev.leonardo.octether.domain.usecase.ShareExportUseCase
import dev.leonardo.octether.domain.usecase.UndoRedoUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SessionActionsDelegate"

/**
 * Owns the 24 stateless REST operations previously inlined in [ChatViewModel].
 *
 * Extracted in Phase 3 Task 6 (G cluster).
 *
 * These methods hold NO private [kotlinx.coroutines.flow.StateFlow] — they read other
 * delegates' state via injected providers/callbacks and delegate to UseCases/Repositories.
 * Cross-delegate coordinators ([ChatViewModel.sendParts], [ChatViewModel.revertMessage],
 * [ChatViewModel.abortSession]) stay in [ChatViewModel] because they write to multiple
 * delegates' private state and orchestrate complex flows (send → observe → error →
 * restore draft, halt → revert → reconnect SSE).
 *
 * [abortSession] REST portion (abort + markIdle) lives here; [ChatViewModel] calls it
 * then handles SSE job cancel/restart.
 *
 * NOTE: Intentionally NOT `@Singleton`/`@Inject`. It holds per-ChatViewModel runtime
 * context (the ViewModel's coroutine scope, cross-delegate providers/callbacks) that
 * Hilt cannot supply. ChatViewModel constructs it directly and re-exposes every
 * member as a facade, so UI files are unchanged.
 */
internal class SessionActionsDelegate(
    private val shareExportUseCase: ShareExportUseCase,
    private val undoRedoUseCase: UndoRedoUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val sessionRepository: SessionRepository,
    private val chatRepository: ChatRepository,
    private val sessionStateService: SessionStateService,
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

    // ============ Refresh Tracking ============
    private var lastRefreshTimeMs: Long = 0L

    // ============ Refresh / Sync ============

    /**
     * Refresh session data — reloads messages and session status from REST.
     */
    fun refreshSession() {
        scope.launch {
            refreshAndSync()
        }
    }

    /**
     * Refresh session only if enough time has passed since last refresh.
     * Called from ON_RESUME — avoids unnecessary REST calls during brief app-switches.
     *
     * Only syncs session status and refreshes messages via REST.
     * Does NOT restart sseJob to avoid scroll position reset and data flickering.
     */
    fun refreshIfNeeded() {
        val elapsed = System.currentTimeMillis() - lastRefreshTimeMs
        if (elapsed >= REFRESH_COOLDOWN_MS) {
            refreshSession()
        }
    }

    /**
     * Query the OpenCode server for the actual session status and correct
     * any UI state drift caused by missed SSE events.
     *
     * Triggered on entering a session and resuming from background.
     * Delegates to [SessionStateService.requestValidation] — the FSM's
     * forceComplete mechanism handles incomplete-message fixing when REST
     * confirms Idle.
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
     * Combined refresh + sync — runs in a single coroutine to avoid
     * state conflicts between parallel REST responses.
     *
     * Status validation delegates to [SessionStateService.requestValidation];
     * the FSM's forceComplete mechanism handles incomplete-message fixing.
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

    // ============ Permission / Question ============

    /**
     * Reply to a permission request.
     * @param requestId The permission request ID
     * @param reply One of: "once", "always", "reject"
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
     * Reply to a question request.
     * @param requestId The question request ID
     * @param answers Answers for each question (list of selected labels per question)
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
     * Reject a question request.
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

    // ============ Share / Export / Compact ============

    /** Share the current session. Returns the share URL or null on failure. */
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

    /** Compact (summarize) the current session. */
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
     * Export the session as JSON directly to a file URI.
     * Streams API responses directly to the output stream to avoid OOM
     * on large sessions (messages can be 80+ MB).
     * Shows a notification with download progress.
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

    // ============ Undo / Redo ============

    /** Undo the last user message in the session, restoring its text to the input field. */
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

    /** Redo the last undone message. */
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
     * Extract text and image URIs from a [ChatMessage] for draft restoration.
     * Pure function — also used by [ChatViewModel.revertMessage] coordinator.
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

    // ============ Message Operations ============

    /** Delete a message from the current session. */
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

    /** Delete a specific part from a message by index. */
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

    // ============ Session Operations ============

    /**
     * Called when a SessionUpdated SSE event is received.
     * Refreshes the message list to pick up revert/unrevert changes.
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

    /** Fork the current session. Returns the new session or null. */
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

    /** Rename the current session. */
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
     * Abort REST call — cancels the session on the server and marks it idle
     * via the FSM (ClientAbort → Idle + forceComplete messages).
     * SSE job cancel/restart is handled by [ChatViewModel.abortSession] coordinator.
     */
    suspend fun abortSession() {
        sessionRepository.abort(serverId, sessionId, sessionDirectoryProvider())
        if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
        sessionStateService.onClientAbort(sessionId)
    }

    // ============ Commands ============

    /** Execute a server-side command (e.g. /init, /review, MCP commands). */
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

    /** Execute shell command in current session. */
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

    // ============ Helpers ============

    /** Get the last assistant message text for copying. */
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
