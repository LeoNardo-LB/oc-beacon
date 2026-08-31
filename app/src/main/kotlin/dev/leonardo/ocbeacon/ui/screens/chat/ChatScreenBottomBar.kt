package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.util.copyToClipboard
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.screens.chat.input.BusyIndicatorSmoother
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatAttachmentsHandler
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatInputBar
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatInputMode
import dev.leonardo.ocbeacon.ui.screens.chat.util.ImageAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.PromptBuilder
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** @file 提及正则：光标前最后一个 @query（onValueChange / 文件选择共用，L-7 预编译）。 */
private val AT_MENTION_REGEX = Regex("@(\\S*)$")

/** 斜杠命令参数分割（发送时解析 /cmd args）。 */
private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

/**
 * 从 ChatScreen 中抽取的底部栏 composable。
 *
 * 包含聊天输入栏及其全部关联逻辑：文本编辑、shell 模式、
 * 斜杠命令、文件提及、附件、模型选择与发送处理。
 *
 * 内部重新获取 [LocalView] 和 [LocalClipboard] —— 这些
 * 环境值在整个组合树中返回同一实例。（原 [LocalContext] 仅供资源读取，
 * #106 lint 清偿后已由 stringResource 取代）
 */
@Composable
internal fun ChatScreenBottomBar(
    viewModel: ChatViewModel,
    sessionMeta: SessionMetaState,
    isTerminalMode: Boolean,
    messageState: MessageListState,
    interaction: InteractionState,
    modelConfig: ModelConfigState,
    isShellMode: Boolean,
    hapticEnabled: Boolean,
    fileSearchResults: List<String>,
    confirmedFilePaths: Set<String>,
    confirmBeforeSend: Boolean,
    attachments: List<ImageAttachment>,
    attachmentHandler: ChatAttachmentsHandler,
    restoredDraft: RevertedDraftPayload?,
    onNavigateToSession: (String) -> Unit,
    inputText: TextFieldValue,
    onInputTextChange: (TextFieldValue) -> Unit,
    onInputModeChange: (String) -> Unit,
    onForceScroll: () -> Unit,
    onShowModelPicker: () -> Unit,
    onShowRenameDialog: () -> Unit,
    onShowSendConfirmDialog: () -> Unit,
    onPendingSendActionSet: ((() -> Unit)?) -> Unit,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onQuickNavigate: () -> Unit = {},
) {
    val view = LocalView.current
    val clipboard = LocalClipboard.current
    val taskUi by viewModel.taskUiState.collectAsStateWithLifecycle()
    val taskToolbarText = if (taskUi.foregroundSubagentCount > 0) {
        stringResource(R.string.task_toolbar_subagents, taskUi.foregroundSubagentCount)
    } else ""
    // #276 能力位门控：DSH 无 command 执行端点——斜杠命令面板与 /cmd 发送拦截均停用
    val serverCapabilities by viewModel.serverCapabilities.collectAsStateWithLifecycle()
    val slashCommandsSupported = serverCapabilities.commandsSupported
    // #276 后端接口补全：DSH 无 shell 域——shell 模式入口（！ 前缀自动切换/
    //   长按切换/面板 shell 项）与 shell 发送全部停用，！ 前缀按普通消息发送。
    val shellCommandSupported = serverCapabilities.shellCommandSupported
    // #276 后端接口补全：DSH 无 revert/unrevert——undo/redo 停用（消息长按撤销
    //   入口在 ChatMessageList 同位门控）。
    val revertSupported = serverCapabilities.revertSupported
    // 权限预设切换器（DSH 专属）：能力位门控 + 会话 permissions 投影驱动回显
    val permissionSwitchSupported = serverCapabilities.permissionSwitchSupported

    // #106 lint 清偿（LocalContextGetResourceValueCall）：snackbar 文案 hoist 到
    // 组合层 stringResource（lambda 内不可调用 @Composable）；带参格式串 hoist
    // 模板、调用点 .format()（保留 locale 占位符次序）
    val shellEmptyMsg = stringResource(R.string.chat_shell_empty)
    val shellAttachmentsUnsupportedMsg = stringResource(R.string.chat_shell_attachments_unsupported)
    val shellFailedMsg = stringResource(R.string.chat_shell_failed)
    val cmdExecutedTpl = stringResource(R.string.chat_command_executed)
    val cmdFailedTpl = stringResource(R.string.chat_command_failed)
    val sessionCompactFailedMsg = stringResource(R.string.chat_session_compact_failed)
    val forkFailedMsg = stringResource(R.string.chat_fork_failed)
    val shareUrlCopiedMsg = stringResource(R.string.chat_share_url_copied)
    val shareFailedMsg = stringResource(R.string.chat_share_failed)
    val sessionUnsharedMsg = stringResource(R.string.chat_session_unshared)
    val sessionUnshareFailedMsg = stringResource(R.string.chat_session_unshare_failed)
    val messageUndoneMsg = stringResource(R.string.chat_message_undone)
    val messageUndoFailedMsg = stringResource(R.string.chat_message_undo_failed)
    val messageRedoneMsg = stringResource(R.string.chat_message_redone)
    val messageRedoFailedMsg = stringResource(R.string.chat_message_redo_failed)
    val permissionCustomMsg = stringResource(R.string.permission_custom_hint)
    val permissionSwitchFailedMsg = stringResource(R.string.permission_switch_failed)

    if (sessionMeta.sessionParentId == null && !isTerminalMode && interaction.error == null) {
        val modelLabel = if (modelConfig.selectedModelId != null && modelConfig.providers.isNotEmpty()) {
            val provider = modelConfig.providers.find { it.id == modelConfig.selectedProviderId }
            val model = provider?.models?.get(modelConfig.selectedModelId)
            model?.name ?: modelConfig.selectedModelId
        } else ""
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .imePadding()
        ) {
            ChatInputBar(
                textFieldValue = inputText,
                onTextFieldValueChange = { newValue ->
                    val wasEmpty = inputText.text.isEmpty()
                    // #253 后续加固（2026-08-28）：前导空白不挡 shell 触发——真机 E2E
                    // 实证「空格 + !cmd」整体回落普通消息（uiautomator 直读字段文本
                    // 前导 0x20）。trimStart 后再检测/剥离。#252 E2E 补充：中文 IME 环境
                    // 下「!」会偶发落成全角「！」（真机条带 exit 127 实证），检测同时
                    // 接受两种形态（drop(1) 对两者均剥单字符）。
                    val trimmed = newValue.text.trimStart()
                    // #276：! 前缀自动切 shell 仅在 shell 域可用时；DSH 下按普通文本
                    val shouldAutoShell = shellCommandSupported && !isShellMode &&
                        (trimmed.startsWith("!") || trimmed.startsWith("！"))
                    val normalizedValue = if (shouldAutoShell) {
                        val stripped = trimmed.drop(1).trimStart()
                        TextFieldValue(
                            text = stripped,
                            selection = TextRange(stripped.length)
                        )
                    } else {
                        newValue
                    }

                    if (shouldAutoShell) {
                        onInputModeChange(ChatInputMode.SHELL.name)
                    }

                    onInputTextChange(normalizedValue)
                    viewModel.composer.updateDraftText(normalizedValue.text)

                    // reverseLayout=true 锚定底部；输入时无需显式滚动。

                    if (isShellMode || shouldAutoShell) {
                        viewModel.composer.clearFileSearch()
                        return@ChatInputBar
                    }
                    // 检测光标前的 @query 以进行文件提及
                    val cursorPos = normalizedValue.selection.start
                    val textBefore = normalizedValue.text.substring(0, cursorPos)
                    val atMatch = AT_MENTION_REGEX.find(textBefore)
                    if (atMatch != null) {
                        val query = atMatch.groupValues[1]
                        viewModel.composer.searchFilesForMention(query)
                    } else {
                        viewModel.composer.clearFileSearch()
                    }
                },
                onSend = {
                    val doSend = doSend@{
                        if (hapticEnabled) {
                            @Suppress("DEPRECATION")
                            val flags = android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM, flags)
                            } else {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK, flags)
                            }
                        }
                        val rawText = inputText.text
                        // #253 后续加固：发送侧兜底同样容许前导空白与全角「！」
                        // （与检测侧同语义）。
                        val trimmedRaw = rawText.trimStart()
                        // #276：！ 前缀仅在 shell 域可用时视为 shell 命令；DSH 下
                        // 按普通消息发送（不进 runShellCommand——能力位外再兜底）。
                        val shellCommand = when {
                            isShellMode -> trimmedRaw
                            shellCommandSupported &&
                                (trimmedRaw.startsWith("!") || trimmedRaw.startsWith("！")) ->
                                trimmedRaw.drop(1).trimStart()
                            else -> null
                        }
                        if (shellCommand != null) {
                            if (shellCommand.isBlank()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(shellEmptyMsg)
                                }
                                return@doSend
                            }
                            if (attachments.isNotEmpty()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(shellAttachmentsUnsupportedMsg)
                                }
                                return@doSend
                            }
                            viewModel.runShellCommand(shellCommand) { ok ->
                                if (!ok) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(shellFailedMsg)
                                    }
                                }
                            }
                            onInputTextChange(TextFieldValue(""))
                            if (isShellMode) {
                                onInputModeChange(ChatInputMode.NORMAL.name)
                            }
                            viewModel.composer.clearConfirmedPaths()
                            viewModel.composer.clearFileSearch()
                            viewModel.composer.clearDraft()
                            onForceScroll()
                            return@doSend
                        }
                        // 检测斜杠命令（例如 /skillname arguments）
                        // #276 能力位门控：DSH 无 command 域——"/xxx" 按普通消息发送
                        if (slashCommandsSupported &&
                            rawText.startsWith("/") && !rawText.startsWith("/ ") && confirmedFilePaths.isEmpty()) {
                            val parts = rawText.trim().split(WHITESPACE_SPLIT_REGEX, 2)
                            val commandName = parts[0].removePrefix("/")
                            val commandArgs = parts.getOrElse(1) { "" }
                            if (commandName.isNotBlank()) {
                                viewModel.executeCommand(commandName, commandArgs) { ok ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) cmdExecutedTpl.format(commandName)
                                            else cmdFailedTpl.format(commandName)
                                        )
                                    }
                                }
                                onInputTextChange(TextFieldValue(""))
                                if (isShellMode) {
                                    onInputModeChange(ChatInputMode.NORMAL.name)
                                }
                                viewModel.composer.clearConfirmedPaths()
                                viewModel.composer.clearFileSearch()
                                viewModel.composer.clearDraft()
                                onForceScroll()
                                return@doSend
                            }
                        }
                        // 构建 prompt parts：围绕已确认的 @file 提及拆分文本
                        val allParts = PromptBuilder.buildPromptParts(rawText, confirmedFilePaths, viewModel.getSessionDirectory())
                        // 添加图片附件
                        val attachmentParts = attachments.map { att ->
                            PromptPart(
                                type = "file",
                                mime = att.mime,
                                url = att.dataUrl,
                                filename = att.filename
                            )
                        }
                        viewModel.sendMessage(allParts, attachmentParts, rawText)
                        // 2026-08-11 用户要求：输入框不在发送时立即清空——
                        // 发送成功由 ViewModel.sendSuccessTick 信号驱动清空（ChatScreen 监听，
                        // 含附件/文件提及/草稿）；发送失败 → 输入区内容完全保留 + AlertDialog。
                        onForceScroll()
                    }
                    if (confirmBeforeSend) {
                        onPendingSendActionSet(doSend)
                        onShowSendConfirmDialog()
                    } else {
                        doSend()
                    }
                },
                inputMode = if (isShellMode) ChatInputMode.SHELL else ChatInputMode.NORMAL,
                onInputModeChange = {
                    // #276：SHELL 模式入口能力位门控——发送钮长按切换在 DSH 下
                    // 无效（shell 域缺失），保持 NORMAL。
                    if (it == ChatInputMode.SHELL && !shellCommandSupported) return@ChatInputBar
                    onInputModeChange(it.name)
                    if (it == ChatInputMode.SHELL) {
                        viewModel.composer.clearFileSearch()
                    }
                },
                isSending = interaction.isSending,
                // 2026-08-17 修复（busy 指示闪烁）：显示侧下降沿消抖——
                // FSM 原始 isBusy 在 V2 drain 窗口会 Busy↔Idle 循环（L3 正向
                // 对账复活 Busy），语义正确但按钮视觉抖动。stableBusy：true
                // 立即传导，false 需稳定 2.5s；isSending 参与（吸收 POST 完成
                // → FSM Busy 接管的组合缝隙）。FSM/单一真相源不动。
                isBusy = rememberStableBusyIndicator(
                    isBusy = sessionMeta.sessionStatus is SessionStatus.Busy ||
                        sessionMeta.sessionStatus is SessionStatus.Retry,
                    isSending = interaction.isSending,
                ),
                // 2026-08-14：等待提问/权限响应时禁用输入框（用户要求）
                inputEnabled = interaction.pendingQuestions.isEmpty() && interaction.pendingPermissions.isEmpty(),
                messages = messageState.messages,
                attachments = attachments,
                onAttach = { attachmentHandler.pickImages() },
                onRemoveAttachment = { index ->
                    if (index in attachments.indices) {
                        attachmentHandler.removeAttachment(index)
                        viewModel.composer.removeDraftAttachment(index)
                    }
                },
                onSaveAttachment = { bytes, mime, filename ->
                    attachmentHandler.requestSaveImage(bytes, mime, filename)
                },
                modelLabel = modelLabel,
                selectedProviderId = modelConfig.selectedProviderId,
                onModelClick = { onShowModelPicker() },
                agents = modelConfig.agents,
                selectedAgent = modelConfig.selectedAgent,
                onAgentSelect = { viewModel.modelSelection.selectAgent(it) },
                variantNames = modelConfig.variantNames,
                selectedVariant = modelConfig.selectedVariant,
                commands = modelConfig.commands,
                slashCommandsSupported = slashCommandsSupported,
                fileSearchResults = fileSearchResults,
                confirmedFilePaths = confirmedFilePaths,
                onFileSelected = { path ->
                    // 用 @path 替换文本中的 @query
                    val cursorPos = inputText.selection.start
                    val textBefore = inputText.text.substring(0, cursorPos)
                    val atMatch = AT_MENTION_REGEX.find(textBefore)
                    if (atMatch != null) {
                        val matchStart = atMatch.range.first
                        val replacement = "@$path "
                        val newText = inputText.text.substring(0, matchStart) + replacement +
                                inputText.text.substring(cursorPos)
                        val newCursor = matchStart + replacement.length
                        onInputTextChange(TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor)
                        ))
                    }
                    viewModel.composer.confirmFilePath(path)
                    viewModel.composer.clearFileSearch()
                },
                onSlashCommand = { cmd ->
                    when (cmd.name) {
                        "new" -> {
                            onNavigateToSession("")  // 空 sessionId = 延迟创建
                        }
                        "compact" -> {
                            onForceScroll()
                            viewModel.compactSession { ok ->
                                // 2026-08-26（用户裁决）：成功不弹 snackbar——分割线
                                // 本身即完成反馈；失败保留提示（静默失败不可接受）。
                                if (!ok) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(sessionCompactFailedMsg)
                                    }
                                }
                            }
                        }
                        "fork" -> {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(forkFailedMsg)
                                    }
                                }
                            }
                        }
                        "share" -> {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboard.copyToClipboard("url", url)
                                        snackbarHostState.showSnackbar(shareUrlCopiedMsg)
                                    } else {
                                        snackbarHostState.showSnackbar(shareFailedMsg)
                                    }
                                }
                            }
                        }
                        "unshare" -> {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) sessionUnsharedMsg else sessionUnshareFailedMsg
                                    )
                                }
                            }
                        }
                        // #276：undo/redo 按 revertSupported 门控（DSH 无 revert
                        // 域；面板本身已按 commandsSupported 隐藏，此为防御性短路）
                        "undo" -> {
                            if (revertSupported) {
                                viewModel.undoMessage { ok ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) messageUndoneMsg else messageUndoFailedMsg
                                        )
                                    }
                                }
                            }
                        }
                        "redo" -> {
                            if (revertSupported) {
                                viewModel.redoMessage { ok ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) messageRedoneMsg else messageRedoFailedMsg
                                        )
                                    }
                                }
                            }
                        }
                        "rename" -> {
                            onShowRenameDialog()
                        }
                        "shell" -> {
                            // #276：shell 模式入口按 shellCommandSupported 门控
                            if (shellCommandSupported) {
                                onInputModeChange(ChatInputMode.SHELL.name)
                            }
                        }
                        "review" -> {
                            onForceScroll()
                            viewModel.executeCommand("review") { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) cmdExecutedTpl.format("review") else cmdFailedTpl.format("review")
                                    )
                                }
                            }
                        }
                        else -> {
                            onForceScroll()
                            viewModel.executeCommand(cmd.name) { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) cmdExecutedTpl.format(cmd.name) else cmdFailedTpl.format(cmd.name)
                                    )
                                }
                            }
                        }
                    }
                },
                onStop = { viewModel.interruptSession() },
                // 堆积消息（2026-08-20 设计定稿）：busy 气泡「堆积」——入队并清空输入框
                onEnqueue = {
                    val text = inputText.text
                    if (text.isNotBlank()) {
                        viewModel.enqueuePendingMessage(text)
                        onInputTextChange(TextFieldValue(""))
                        viewModel.composer.updateDraftText("")
                    }
                },
                restoredDraft = restoredDraft,
                onConsumeRestoredDraft = { viewModel.composer.consumeRestoredDraft() },
                onQuickNavigate = onQuickNavigate,
                showTaskToolbar = taskUi.showTaskToolbar,
                taskToolbarText = taskToolbarText,
                onBackgroundSession = { viewModel.backgroundSession() },
                permissionSwitchSupported = permissionSwitchSupported,
                permissions = sessionMeta.sessionPermissions,
                onPermissionSelect = { preset ->
                    viewModel.setPermissionPreset(preset) { ok ->
                        if (!ok) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(permissionSwitchFailedMsg)
                            }
                        }
                    }
                },
                onPermissionCustomClick = {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(permissionCustomMsg)
                    }
                },
            )
        }
    }
}

/**
 * 发送按钮 busy 指示的显示侧消抖（2026-08-17 修复：流式输出期间进度圈闪烁）。
 *
 * - true（busy 或 sending）立即传导
 * - 两者皆 false 后保持 [BusyIndicatorSmoother.DEFAULT_RELEASE_DELAY_MS] 才释放
 *   （覆盖 V2 drain 窗口 FSM Busy↔Idle 抖动周期与 isSending→isBusy 接管缝隙）
 * - 释放等待期间任一变 true → 取消挂起的释放，立即回 true
 *
 * 只影响输入区视觉（showStop/busySpinner/shell canSend）；abort 等业务逻辑
 * 仍读 FSM 原始状态。FSM 语义与 SessionStateService 单一真相源不变。
 */
@Composable
private fun rememberStableBusyIndicator(isBusy: Boolean, isSending: Boolean): Boolean {
    val smoother = remember { BusyIndicatorSmoother() }
    var stable by remember { mutableStateOf(false) }
    LaunchedEffect(isBusy, isSending) {
        val now = System.currentTimeMillis()
        stable = smoother.update(isBusy, isSending, now)
        val remaining = smoother.remainingMs(now)
        if (remaining > 0) {
            kotlinx.coroutines.delay(remaining)
            stable = smoother.update(false, false, System.currentTimeMillis())
        }
    }
    return stable
}
