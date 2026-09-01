package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageTerminalUseCase
import dev.leonardo.ocbeacon.domain.usecase.ShareExportUseCase
import dev.leonardo.ocbeacon.domain.usecase.UndoRedoUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SessionActionsDelegate"

/**
 * 管理此前内联在 [ChatViewModel] 中的 24 个无状态 REST 操作。
 *
 * 这些方法不持有私有 [kotlinx.coroutines.flow.StateFlow] —— 它们通过注入的
 * provider/回调读取其他 delegate 的状态，并委托给 UseCase/Repository。
 * 跨 delegate 协调器（[ChatViewModel.sendParts]、[ChatViewModel.revertMessage]、
 * [ChatViewModel.interruptSession]）留在 [ChatViewModel] 中，因为它们写入多个
 * delegate 的私有状态并编排复杂流程（发送 → 观察 → 错误 →
 * 恢复草稿，暂停 → revert → 重连 SSE）。
 *
 * [interruptSession] 的 REST 部分（abort + markIdle）在此处；[ChatViewModel] 调用它
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
    private val sessionStateRepository: SessionStateRepository,
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
    /** 2026-08-24（#217 分割线包揽）：压缩异步能力位——true(V2)=HTTP 立即返回，
     *  进行中/终态全由 SSE compaction.* 驱动；false(V1/未知)=HTTP 同步挂起至
     *  完成，返回即终态（进行中态需本地置起、返回时终结）。 */
    private val compactionAsyncProvider: () -> Boolean,
    /** #276 终验 V5：压缩与模型无关能力位（DSH /compact 命令通道）——true 时
     *  跳过「no model selected」前置检查（OpenCode summarize/compact 带模型参数，
     *  默认 false 维持原护栏）。 */
    private val compactionModelIndependentProvider: () -> Boolean = { false },
    /** #217：V1 本地压缩态注入（ChatViewModel 接 EventDispatcher →
     *  SessionNextEventHandler.compactionState 单一数据源）；V2 永不调用。 */
    private val compactionLocalState: (sessionId: String, started: Boolean) -> Unit = { _, _ -> },
    /** #276 后端接口补全：shell 命令域能力位——false（DSH）时 [runShellCommand]
     *  直接短路失败（不发请求——DshApiClient 该域抛 UnsupportedServerCapability）。 */
    private val shellCommandSupportedProvider: () -> Boolean = { true },
    /** #276 终验 V6：导出载荷是 ZIP 归档（DSH session.export）——true 时写盘前把
     *  SAF 文档显示名规范成 .zip；OpenCode 导出是 JSON 文档，默认 false 维持 .json。 */
    private val exportIsArchiveProvider: () -> Boolean = { false },

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
                sessionStateRepository.requestValidation(sessionId)
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
            sessionStateRepository.requestValidation(sessionId)
            // 2026-08-16 根治（回复不可见）：ON_RESUME 无条件 cursor 增量补漏
            //（SSE_PRIORITY）——覆盖 L3 在服务器仍 Busy 时跳过刷新的缺口：
            // 后台冻结断连窗口丢失的回复事件，在服务器 running（含 V2 僵尸
            // drain）期间此前无任何补漏触发点，回复不可见直到用户退出重进。
            sessionStateRepository.backfillMissedMessages(sessionId)
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
    fun replyToPermission(requestId: String, reply: String, sessionId: String? = null) {
        scope.launch {
            val logMsg = "[Permission] replyToPermission: id=$requestId reply=$reply sid=$sessionId dir=${sessionDirectoryProvider()}"
            AppLogger.i(TAG, logMsg)
            try {
                val success = managePermissionUseCase.replyToPermission(
                    serverId = serverId,
                    // 2026-08-17：V2 reply 路由需要权限所属会话（子智能体会话权限必须
                    // 传子智能体会话 id，父会话 404）。缺省降级为当前会话（V1 与同会话场景）。
                    sessionId = sessionId ?: sessionIdProvider(),
                    requestId = requestId,
                    reply = reply,
                    directory = sessionDirectoryProvider()
                )
                val resultMsg = "[Permission] replyToPermission result: id=$requestId success=$success"
                AppLogger.i(TAG, resultMsg)
                // 2026-08-17 根治（权限卡每次进入重弹）：失败不再无条件清卡——
                // 原 fallback 假设「失败=已回复过」不成立：网络/路由失败时服务器
                // 侧仍 pending，清内存只是暂时消失，下次进入 loadPendingPermissions
                // 重新注入 → 用户「每次进入都要重新点」。失败复核服务器：
                // 仍 pending 保留卡片；已不在（真已回复）才移除。
                if (success) {
                    chatRepository.removePermission(requestId)
                } else {
                    removePermissionIfGoneOnServer(requestId, "reply")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errMsg = "[Permission] Exception replying to $requestId: ${e.javaClass.simpleName}: ${e.message}"
                AppLogger.e(TAG, errMsg, e)
                // 异常同理：服务器侧状态未知——复核后决定
                removePermissionIfGoneOnServer(requestId, "reply(exception)")
            }
        }
    }

    /**
     * 2026-08-17 根治（权限卡每次进入重弹）：reply 失败后的去留判定——
     * 复核服务器 pending 列表：该 id 仍存在 → 保留卡片（用户重试）；
     * 已不存在 → 移除。复核自身失败时保守保留（宁多重弹，不静默丢授权）。
     */
    private suspend fun removePermissionIfGoneOnServer(requestId: String, op: String) {
        val stillPending = try {
            managePermissionUseCase.listPendingPermissions(serverId, sessionDirectoryProvider())
                .any { it.id == requestId }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "[Permission] $op failed and re-check also failed for $requestId, keeping card: ${e.message}")
            true
        }
        if (!stillPending) {
            AppLogger.i(TAG, "[Permission] $op failed but server no longer pending $requestId -> remove card")
            chatRepository.removePermission(requestId)
        } else {
            AppLogger.w(TAG, "[Permission] $op failed for $requestId, server still pending -> keep card for retry")
        }
    }

    fun savePermissionRule(event: SseEvent.PermissionAsked, directory: String) {
        scope.launch {
            // 新增P2（2026-08-19）：空 toolName 守卫——部分 ask 事件不带 permission
            // 显示名（如评估端点产生），保存 toolName="" 的规则会让 matches() 把
            // 后续所有空名 ask 误判为命中（实测：2ms 内被 auto-approve 吞掉，
            // 卡片不显示）。空名无语义，跳过保存。
            if (event.permission.isBlank()) {
                AppLogger.w(TAG, "[Permission] skip saving auto-approve rule with blank toolName (request=" + event.id + ")")
                return@launch
            }
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
            AppLogger.i(TAG, logMsg)
            try {
                val success = managePermissionUseCase.replyToQuestion(
                    serverId = serverId,
                    requestId = requestId,
                    answers = answers,
                    directory = sessionDirectoryProvider()
                )
                val resultMsg = "[Question] replyToQuestion result: id=$requestId success=$success"
                AppLogger.i(TAG, resultMsg)
                // 2026-08-17 根治（问题卡片重复弹出）：success=false 时不再
                // 无条件清内存——reply 未到达服务器（网络/契约失败）时服务器
                // 侧 form 仍 pending，清内存只是让卡片暂时消失，下次进入会话
                // loadPendingQuestions 重新注入 → 用户「每次进入都要重新点」。
                // 失败路径：复核服务器侧状态——仍 pending 则保留卡片 + 提示
                // 重试（用户可重点）；服务器已无此 pending（超时清理/已答）才移除。
                if (success) {
                    chatRepository.removeQuestion(requestId)
                } else {
                    removeQuestionIfGoneOnServer(requestId, "reply")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errMsg = "[Question] Exception replying to $requestId: ${e.javaClass.simpleName}: ${e.message}"
                AppLogger.e(TAG, errMsg, e)
                // 异常同理：服务器侧状态未知——复核后决定（不再静默清内存）
                removeQuestionIfGoneOnServer(requestId, "reply(exception)")
            }
        }
    }

    /**
     * 2026-08-17 根治（问题卡片重复弹出）：reply/reject 失败后的去留判定——
     * 复核服务器 pending 列表：该 id 仍存在 → 保留卡片（等待用户重试）；
     * 已不存在（服务器超时清理/已被处理）→ 移除卡片。
     */
    private suspend fun removeQuestionIfGoneOnServer(requestId: String, op: String) {
        val stillPending = try {
            managePermissionUseCase.listPendingQuestions(serverId, sessionDirectoryProvider())
                .any { it.id == requestId }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "[Question] $op failed and re-check also failed for $requestId, keeping card: ${e.message}")
            true // 复核失败时保守保留卡片（宁可多弹一次，不可静默丢回复）
        }
        if (!stillPending) {
            AppLogger.i(TAG, "[Question] $op failed but server no longer pending $requestId -> remove card")
            chatRepository.removeQuestion(requestId)
        } else {
            // 保留卡片（用户可重新提交）；卡片仍在场即视觉反馈，无 snackbar 通道
            AppLogger.w(TAG, "[Question] $op failed for $requestId, server still pending -> keep card for retry")
        }
    }

    /**
     * 拒绝问题请求。
     */
    fun rejectQuestion(requestId: String) {
        scope.launch {
            val logMsg = "[Question] rejectQuestion: id=$requestId dir=${sessionDirectoryProvider()}"
            AppLogger.i(TAG, logMsg)
            try {
                val success = managePermissionUseCase.rejectQuestion(
                    serverId = serverId,
                    requestId = requestId,
                    directory = sessionDirectoryProvider()
                )
                val resultMsg = "[Question] rejectQuestion result: id=$requestId success=$success"
                AppLogger.i(TAG, resultMsg)
                // 2026-08-17 根治（问题卡片重复弹出）：与 replyToQuestion 同理——
                // reject 未到达服务器时保留卡片（复核服务器后决定）。
                if (success) {
                    chatRepository.removeQuestion(requestId)
                } else {
                    removeQuestionIfGoneOnServer(requestId, "reject")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errMsg = "[Question] Exception rejecting $requestId: ${e.javaClass.simpleName}: ${e.message}"
                AppLogger.e(TAG, errMsg, e)
                removeQuestionIfGoneOnServer(requestId, "reject(exception)")
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
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Shared session $sessionId: $url")
                onResult(url)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to share session", e)
                onResult(null)
            }
        }
    }

    fun unshareSession(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                shareExportUseCase.unshareSession(serverId, sessionId)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Unshared session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to unshare session", e)
                onResult(false)
            }
        }
    }

    /**
     * 压缩当前会话（2026-08-24 #217 分割线包揽重构）。
     *
     * - V2（compactionAsync=true）：HTTP 立即返回（steer 异步）。进行中态由 SSE
     *   compaction.started 置起、delta 流式累积、ended/failed 终结——本地不再
     *   注入任何压缩状态（旧 finally 秒杀 banner 的 59ms 闪现根因）。
     * - V1（compactionAsync=false）：HTTP 同步挂起至压缩完成、SSE 无 started——
     *   本地置起进行中态，返回/异常即终结（行为等价旧链路，但只驱动分割线）。
     */
    fun compactSession(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val config = modelConfigProvider()
                val providerId = config.selectedProviderId
                val modelId = config.selectedModelId
                // #276 终验 V5：DSH /compact 与模型无关（命令通道）——护栏按能力位
                // 旁路；OpenCode V1/V2 端点带 providerID/modelID，维持原拦截。
                if (!compactionModelIndependentProvider() && (providerId == null || modelId == null)) {
                    AppLogger.e(TAG, "Cannot compact: no model selected")
                    onResult(false)
                    return@launch
                }
                val isAsync = compactionAsyncProvider()
                if (!isAsync) {
                    compactionLocalState(sessionId, true)
                }
                try {
                    // DSH 旁路时无模型选择——空串占位（DshApiClient 对 /compact 忽略两参）。
                    shareExportUseCase.compactSession(serverId, sessionId, providerId.orEmpty(), modelId.orEmpty())
                    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Compacted session $sessionId")
                    onResult(true)
                } finally {
                    // V1：HTTP 返回即终态。V2 正常路径由 SSE ended 终结，不本地杀。
                    if (!isAsync) {
                        compactionLocalState(sessionId, false)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to compact session", e)
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
                // DSH session.export 响应体是 ZIP 流——SAF CreateDocument 的 MIME 与建议
                // 名扩展名已按 exportIsArchive 能力位切换（#279：ChatAttachmentsHandler /
                // ChatScreen）；此处 renameDocument 仅作落盘后兜底（provider 不支持
                // renameDocument 或非文档 URI 时静默回退原名）。OpenCode 导出是 JSON
                // 文档（exportIsArchive=false），维持 .json 命名。
                val targetUri = if (exportIsArchiveProvider()) renameToZipExtension(context, uri) else uri
                AppLogger.d(TAG, "exportSession: streaming to $targetUri")
                notificationManager.notify(notificationId, builder.build())

                var lastNotifyTime = 0L
                context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
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

                AppLogger.d(TAG, "exportSession: done")
                notificationManager.cancel(notificationId)
                withContext(Dispatchers.Main) {
                    onResult(true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to export session", e)
                // 2026-08-16 修复（通知 P2）：导出失败不再直接 cancel 通知——
                // 用户离开应用后导出失败无任何感知。改发失败通知（同 channel，
                // 静音自动消失，文本用已有 chat_session_export_failed）。
                builder.setContentTitle(context.getString(dev.leonardo.ocbeacon.R.string.chat_session_export_failed))
                    .setContentText(e.message?.take(80) ?: "")
                    .setProgress(0, 0, false)
                    .setAutoCancel(true)
                notificationManager.notify(notificationId, builder.build())
                withContext(Dispatchers.Main) {
                    onResult(false)
                }
            }
        }
    }

    // ============ #276 终验 V6：ZIP 导出显示名规范 ============

    /** 写盘前把 SAF 文档显示名规范成 .zip；provider 不支持/查询失败静默回退原 URI。 */
    private fun renameToZipExtension(context: android.content.Context, uri: android.net.Uri): android.net.Uri {
        val displayName = queryDisplayName(context, uri) ?: return uri
        val newName = zipExportDisplayName(displayName)
        if (newName == displayName) return uri
        return runCatching {
            android.provider.DocumentsContract.renameDocument(context.contentResolver, uri, newName)
        }.getOrNull() ?: uri
    }

    /** 查询 SAF 文档显示名（DocumentsContract）；非文档 URI/查询失败 → null。 */
    private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    // ============ 撤销 / 重做 ============

    /** 撤销会话中最后一条用户消息，将其文本恢复到输入框。
     *  2026-08-15（research/10 P0）：消息列表为升序（MessageDataDelegate:187），
     *  原 `firstOrNull{it.isUser}` 取到**最旧** user 消息——undo 一直撤销到
     *  会话开头。官方语义（TUI index.tsx:621/Web :341）：最后一条可见 user。
     *  改 lastOrNull；连续 undo 语义（revert 存在时取边界前一条）由服务器
     *  staged revert 天然支持（每次 revert 推进边界）。 */
    fun undoMessage(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val messages = messageListProvider()
                val lastUser = messages.lastOrNull { it.isUser }
                if (lastUser == null) {
                    onResult(false)
                    return@launch
                }
                undoRedoUseCase.revertSession(serverId, sessionId, lastUser.message.id)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Reverted session $sessionId to message ${lastUser.message.id}")
                restoreRevertedDraft(extractRevertedDraft(lastUser))
                onResult(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to revert session", e)
                onResult(false)
            }
        }
    }

    /** Redo 最后一次撤销的消息。 */
    fun redoMessage(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                undoRedoUseCase.unrevertSession(serverId, sessionId)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Unreverted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to unrevert session", e)
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
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Deleted message $messageId: success=$success")
                onResult(success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to delete message $messageId", e)
                onResult(false)
            }
        }
    }

    /** 通过索引从消息中删除特定 part。 */
    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val success = manageSessionUseCase.deleteMessagePart(serverId, sessionId, messageId, partIndex)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Deleted part $partIndex from message $messageId: success=$success")
                onResult(success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to delete part $partIndex from message $messageId", e)
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
                chatRepository.upsertMessages(sessionId, messages, MergeStrategy.REST_AUTHORITY)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Refreshed messages after session update")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to refresh messages after session update", e)
            }
        }
    }

    /** Fork 当前会话。返回新会话或 null。 */
    fun forkSession(onResult: (Session?) -> Unit) {
        scope.launch {
            try {
                val session = manageSessionUseCase.forkSession(serverId, sessionId)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Forked session $sessionId -> ${session.id}")
                onResult(session)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }

    /** 重命名当前会话。 */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                manageSessionUseCase.renameSession(serverId, sessionId, title)
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Renamed session $sessionId to $title")
                onResult(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to rename session", e)
                onResult(false)
            }
        }
    }

    /**
     * Abort REST 调用 —— 在服务器上取消会话并通过
     * FSM（ClientAbort → Idle + forceComplete 消息）标记为 idle。
     * SSE job 的取消/重启由 [ChatViewModel.interruptSession] 协调器处理。
     */
    suspend fun interruptSession() {
        sessionRepository.interrupt(serverId, sessionId, sessionDirectoryProvider())
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Aborted session $sessionId")
        sessionStateRepository.onClientAbort(sessionId)
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
                    AppLogger.d(
                        TAG,
                        "Executed command /$normalizedCommand in session $currentSessionId: $ok (directory=$effectiveDirectory, arguments=$effectiveArguments)"
                    )
                }
                onResult(ok)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to execute command /$command", e)
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
        // #276 能力位短路：DSH 无 shell 域（DshApiClient 抛 UnsupportedServer
        // Capability）——UI 入口已按 shellCommandSupported 隐藏，此处兜底残留
        // 路径（如切换服务器后 isShellMode 态残留）：不发请求直接失败。
        if (!shellCommandSupportedProvider()) {
            AppLogger.w(TAG, "runShellCommand skipped: shell command not supported by server")
            onResult(false)
            return
        }
        scope.launch {
            try {
                // 2026-08-28（#250 真机 E2E 取证）：新会话首发 shell 时 sessionId 尚未
                // 就位——ensureSession 此前只挂在普通消息（ChatSendDelegate）与斜杠命令
                // （executeCommand）路径上，shell 直读当前值 → POST /api/session//shell
                // 空 id 404 →「Shell 命令运行失败」。对齐 executeCommand（:636）同款模式：
                // 先 ensureSession（幂等 + mutex 双检，已有会话瞬时返回）。
                val currentSessionId = ensureSession()
                val modelCfg = modelConfigProvider()
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId,
                        modelId = modelCfg.selectedModelId
                    )
                } else null
                val ok = manageTerminalUseCase.runShellCommand(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    command = trimmed,
                    agent = modelCfg.selectedAgent,
                    model = model,
                    directory = sessionDirectoryProvider()
                )
                if (BuildConfig.DEBUG) AppLogger.d(TAG, "Executed shell command in session $currentSessionId: $ok")
                onResult(ok)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "Failed to execute shell command", e)
                onResult(false)
            }
        }
    }

    // ============ 辅助方法 ============

    /** 获取最后一条 assistant 消息文本以供复制。
     *  2026-08-15（research/10 P0）：升序列表取 firstOrNull = 最旧 assistant——
     *  改 lastOrNull（同 undoMessage 修复）。 */
    fun getLastAssistantText(): String? {
        val msgs = messageListProvider()
        val last = msgs.lastOrNull { it.isAssistant } ?: return null
        return last.parts
            .filterIsInstance<Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { null }
    }

    companion object {
        const val REFRESH_COOLDOWN_MS = 5_000L
    }
}

/**
 * #276 终验 V6：ZIP 归档导出（DSH session.export）的显示名规范。
 * SAF 建议名固定 $slug.json（ChatScreen，本轮冻结）而载荷是 ZIP：
 * .json 后缀换 .zip；无后缀/他后缀补 .zip（无损）；已是 .zip 不动。
 */
internal fun zipExportDisplayName(current: String): String {
    val name = current.trim()
    return when {
        name.isEmpty() -> "session.zip"
        name.endsWith(".zip", ignoreCase = true) -> name
        name.endsWith(".json", ignoreCase = true) -> name.dropLast(5) + ".zip"
        else -> name + ".zip"
    }
}
