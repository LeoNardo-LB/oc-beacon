package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import androidx.compose.runtime.setValue
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog
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
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.screens.chat.input.BusyIndicatorSmoother
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatAttachmentsHandler
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatInputBar
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatInputMode
import dev.leonardo.ocbeacon.ui.screens.chat.input.isQuotedMentionQuery
import dev.leonardo.ocbeacon.ui.screens.chat.input.PlanChipGate
import dev.leonardo.ocbeacon.ui.screens.chat.util.ImageAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.PromptBuilder
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommand
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommandRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** @file 提及正则：光标前最后一个 @query（onValueChange / 文件选择共用，L-7 预编译）。 */
private val AT_MENTION_REGEX = Regex("@(\\S*)$")

// #310⑤ @" 引号形态判定（input 包纯函数,单测 MentionQueryTest）

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
    onShowRenameDialog: (String?) -> Unit,
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
    val slashCommandsSupported = ServerFeatures.COMMANDS in serverCapabilities
    // #356：busy 气泡菜单态（立即发送[steer]/消息排队[queue]；V1 无队列域仅前者）
    var showBusyMenu by remember { mutableStateOf(false) }
    // 稳定 busy 指示提升（原内联于 ChatInputBar 参数——#348 拦截发送需要读它）
    val stableBusy = rememberStableBusyIndicator(
        isBusy = sessionMeta.sessionStatus is SessionStatus.Busy ||
            sessionMeta.sessionStatus is SessionStatus.Retry,
        isSending = interaction.isSending,
    )
    // #310① 子会话续聊门控：DSH 子会话 mode=continuable → 解禁 composer
    //（one-shot 只读提示行）；加载中/失败保守隐藏——防 one-shot 误发。
    val serverType by viewModel.serverType.collectAsStateWithLifecycle()
    // #310⑤ 会话源候选（与 fileSearchResults 同源同清,composer 统一状态）
    val sessionMentionResults by viewModel.composer.sessionSearchResults.collectAsStateWithLifecycle()
    val subagentMode by viewModel.subagentModeState.collectAsStateWithLifecycle()
    val subagentComposerVisible = SubagentComposerGate.composerVisible(
        sessionMeta.sessionParentId, serverType, subagentMode,
    )
    // #276 后端接口补全：DSH 无 shell 域——shell 模式入口（！ 前缀自动切换/
    //   长按切换/面板 shell 项）与 shell 发送全部停用，！ 前缀按普通消息发送。
    val shellCommandSupported = ServerFeatures.SHELL in serverCapabilities
    // #276 后端接口补全：DSH 无 revert/unrevert——undo/redo 停用（消息长按撤销
    //   入口在 ChatMessageList 同位门控）。
    val revertSupported = ServerFeatures.SESSION_REVERT in serverCapabilities
    // 权限预设切换器（DSH 专属）：能力位门控 + 会话 permissions 投影驱动回显
    val permissionSwitchSupported = ServerFeatures.PERMISSION_SWITCH in serverCapabilities
    // #310③ Plan 状态 chip（DSH-only）：投影 {active,pending} 驱动——无投影/有效
    // 目标态为关时不出 chip（PlanChipGate 纯逻辑，单测钉死）
    val planState by viewModel.planState.collectAsStateWithLifecycle()
    val planChipVisible = PlanChipGate.chipVisible(serverType, planState)
    val planExitFailedMsg = stringResource(R.string.plan_exit_failed)

    // #106 lint 清偿（LocalContextGetResourceValueCall）：snackbar 文案 hoist 到
    // 组合层 stringResource（lambda 内不可调用 @Composable）；带参格式串 hoist
    // 模板、调用点 .format()（保留 locale 占位符次序）
    val shellEmptyMsg = stringResource(R.string.chat_shell_empty)
    val shellAttachmentsUnsupportedMsg = stringResource(R.string.chat_shell_attachments_unsupported)
    val shellFailedMsg = stringResource(R.string.chat_shell_failed)
    // #312④：命令带图拦截提示（服务器 commands/execute 图片按命令声明门控，
    // app 命令链无图可传——统一发送前拦截）
    val commandImagesUnsupportedMsg = stringResource(R.string.chat_command_images_unsupported)
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
    // #309 批1③：Full access 二次确认（Web 对位——danger-full-access 档点选后弹确认）
    val fullAccessTitle = stringResource(R.string.permission_full_access_confirm_title)
    val fullAccessMsg = stringResource(R.string.permission_full_access_confirm_message)
    val fullAccessConfirmLabel = stringResource(R.string.permission_full_access_confirm_button)
    var confirmFullAccess by remember { mutableStateOf(false) }
    val executePermissionSwitch: (String) -> Unit = { preset ->
        viewModel.setPermissionPreset(preset) { ok ->
            if (!ok) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(permissionSwitchFailedMsg)
                }
            }
        }
    }
    if (confirmFullAccess) {
        ConfirmDialog(
            title = fullAccessTitle,
            message = fullAccessMsg,
            confirmLabel = fullAccessConfirmLabel,
            onDismiss = { confirmFullAccess = false },
            onConfirm = {
                confirmFullAccess = false
                executePermissionSwitch("danger-full-access")
            },
        )
    }

    // #309 批1④：直发插话（steer）——忙碌时长按发送键触发（空闲长按维持 shell 切换，
    // 语义正交：shell+忙碌本就禁用）。发送主链原样提升为本函数：steer 仅改写 DSH
    // session.prompt 的 mode（queue→steer，注入进行中轮次），confirm/shell/斜杠判定全共用。
    // 2026-09-09（G2-① 根修）：上提为局部 val——doSend 打字路径客户端命令
    // 分流与建议面板 tap 共用同一分发口（此前仅面板 tap 可达）。
    val handleSlashCommand: (SlashCommand) -> Unit = { cmd ->
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
                // 打字路径 /rename <new> 携参预填（args=null 的面板 tap 维持旧标题起点）
                onShowRenameDialog(cmd.args)
            }
            "shell" -> {
                // #276：shell 模式入口按 shellCommandSupported 门控
                if (shellCommandSupported) {
                    onInputModeChange(ChatInputMode.SHELL.name)
                }
            }
            // #380 死代码清理（2026-09-09）：#372 回填铁律后 onSlashCommand 仅收
            // client 型（九注册名全部显式分支）；原 "review" 分支（review 从不在
            // client 注册表）与 else 服务端派遣分支均不可达——移除。未知 client
            // 命令 no-op（注册表扩展时须显式加分支）。
            else -> {
            }
        }
    }

    val sendFromComposer: (Boolean) -> Unit = { steer ->
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
                        // #312④ 命令带图拦截：服务器命令通道仅 input.images 声明
                        // 命令接受图片（dsh-commands 契约，"capable composers refuse
                        // the submission before dispatch"）；app executeCommand 链
                        // 无图可传——不拦则附件被静默丢弃（无 @file 时）或命令文本
                        // 连图掉 prompt 通道直接喂模型（有 @file 时）。
                        if (slashCommandsSupported &&
                            dev.leonardo.ocbeacon.ui.screens.chat.input.SlashCommandGate.blocksImages(
                                rawText, attachments.size,
                            )
                        ) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(commandImagesUnsupportedMsg)
                            }
                            return@doSend
                        }
                        // 检测斜杠命令（例如 /skillname arguments）
                        // #365（2026-09-08 用户裁决）：**只有注册在册的原生命令**
                        //（commands/list：compact/export/feedback/goal/permission/plan
                        // 等会话操作类）走命令通道；技能斜杠（/calculator 等，不在
                        // 命令注册表）一律按普通消息发送——上屏入转录，由会话 agent
                        // 调起技能（服务器实证：消息面 → run_code → bash → python3
                        // 技能脚本 → 回复可见；命令通道对未注册名受理即蒸发）。
                        if (slashCommandsSupported &&
                            rawText.startsWith("/") && !rawText.startsWith("/ ") && confirmedFilePaths.isEmpty()) {
                            val parts = rawText.trim().split(WHITESPACE_SPLIT_REGEX, 2)
                            val commandName = parts[0].removePrefix("/")
                            val commandArgs = parts.getOrElse(1) { "" }
                            if (commandName.isNotBlank() && modelConfig.commands.any { it.name == commandName }) {
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
                            // 2026-09-09（G2-① 根修）：打字路径客户端命令分流——未注册服务器命令名
                            // 的斜杠文本（如 /rename testx）此前静默落 prompt 通道喂模型（实测烧
                            // 一轮 LLM 重试）。客户端命令是 app 本地动词：与面板 tap 同一分发口。
                            if (commandName.isNotBlank() &&
                                SlashCommandRegistry.clientCommandNames.contains(commandName)) {
                                handleSlashCommand(SlashCommand(commandName, null, "client", args = commandArgs.takeIf { it.isNotBlank() }))
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
                        viewModel.sendMessage(allParts, attachmentParts, rawText, steer)
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
    }
    if (subagentComposerVisible && !isTerminalMode && interaction.error == null) {
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
                        // #310⑤：@" 引号形态只拉文件候选（web mod34 先例）
                        viewModel.composer.searchFilesForMention(query, quoted = isQuotedMentionQuery(query))
                    } else {
                        viewModel.composer.clearFileSearch()
                    }
                },
                // #348（2026-09-07 用户裁决定案）：busy+有文本 → 弹气泡菜单
                //（立即发送[服务端排队]/堆积消息[本地轮末自动发]）——取代 #326
                // 单键直排队；空闲路径零改动；长按 steer 旁路保留
                onSend = {
                    if (stableBusy && inputText.text.isNotBlank() && !isShellMode) {
                        showBusyMenu = true
                    } else {
                        sendFromComposer(false)
                    }
                },
                onSendSteer = { sendFromComposer(true) },
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
                // 2026-08-17 修复（busy 指示闪烁）：显示侧下降沿消抖（#348 提升为
                // stableBusy 局部值——onSend 拦截共用同一指示）。
                isBusy = stableBusy,
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
                skills = modelConfig.skills,  // #324⑤ 技能触发组
                slashCommandsSupported = slashCommandsSupported,
                fileSearchResults = fileSearchResults,
                sessionSearchResults = sessionMentionResults,
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
                onSessionSelected = { session ->
                    // #310⑤：以服务器权威 mention 串 @[label](dsh-session:id) 替换 trigger 词
                    // （确认态视觉高亮同文件路径的后续迭代项,见 FileMentionTransformation 注记）
                    val cursorPos = inputText.selection.start
                    val textBefore = inputText.text.substring(0, cursorPos)
                    val atMatch = AT_MENTION_REGEX.find(textBefore)
                    if (atMatch != null) {
                        val matchStart = atMatch.range.first
                        val replacement = session.mention + " "
                        val newText = inputText.text.substring(0, matchStart) + replacement +
                                inputText.text.substring(cursorPos)
                        val newCursor = matchStart + replacement.length
                        onInputTextChange(TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor)
                        ))
                    }
                    viewModel.composer.clearFileSearch()
                },
                onSlashCommand = handleSlashCommand,
                onStop = { viewModel.interruptSession() },
                restoredDraft = restoredDraft,
                onConsumeRestoredDraft = { viewModel.composer.consumeRestoredDraft() },
                onQuickNavigate = onQuickNavigate,
                showTaskToolbar = taskUi.showTaskToolbar,
                taskToolbarText = taskToolbarText,
                onBackgroundSession = { viewModel.backgroundSession() },
                permissionSwitchSupported = permissionSwitchSupported,
                permissions = sessionMeta.sessionPermissions,
                onPermissionSelect = { preset ->
                    // #309 批1③：danger-full-access 先确认再切换（其余档直切）
                    if (preset == "danger-full-access") {
                        confirmFullAccess = true
                    } else {
                        executePermissionSwitch(preset)
                    }
                },
                onPermissionCustomClick = {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(permissionCustomMsg)
                    }
                },
                planChipVisible = planChipVisible,
                planPending = planState?.pending == true,
                onPlanExit = {
                    // #310③：经既有 commands/execute 斜杠命令链发 /plan off；
                    // 回显由 plan 投影帧驱动（active+pending → chip 退场），不乐观置态
                    viewModel.exitPlanMode { ok ->
                        if (!ok) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(planExitFailedMsg)
                            }
                        }
                    }
                },
            )
            // #356 busy 气泡菜单（Popup 锚 composer 右下、气泡上弹；点外/返回关闭）
            // #361（2026-09-08 用户二度裁决）：菜单弹出**不得收键盘**——
            // Popup focusable=false（不夺窗口焦点，IME 保持）；返回键改由
            // BackHandler 承接（非聚焦弹窗收不到 key 事件，见下）。
            BackHandler(enabled = showBusyMenu) { showBusyMenu = false }
            if (showBusyMenu) {
                BusySendMenuPopup(
                    hasAttachments = attachments.isNotEmpty(),
                    showQueueOption = ServerFeatures.QUEUE in serverCapabilities,
                    onDismiss = { showBusyMenu = false },
                    onSendNow = {
                        showBusyMenu = false
                        // 立即发送=steer：注入进行中轮次——受理即上屏+排队徽标
                        //（DSH session.prompt mode=steer / V2 delivery=steer；
                        // V1 无档位参数，忽略 steer 即普通发送）。
                        sendFromComposer(true)
                    },
                    onQueue = {
                        showBusyMenu = false
                        // 消息排队=queue：入服务端队列，轮末自动派发——
                        // 上屏+徽标（echo 播种）+QueueSheet 列表可见。
                        sendFromComposer(false)
                    },
                )
            }
        }
    }
    // #310① one-shot 子会话只读提示行（明确不可续聊；加载中/失败保持全隐藏——
    // 与 composer 同位替换渲染，占位底部栏避免布局跳变）
    if (SubagentComposerGate.readOnlyHintVisible(sessionMeta.sessionParentId, serverType, subagentMode) &&
        !isTerminalMode
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = stringResource(R.string.chat_subagent_one_shot_read_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
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
 * 只影响输入区视觉（单键形态/busy 指示/shell canSend，#326）；abort 等业务逻辑
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
// ============ #356 消息排队与 busy 气泡菜单组件 ============

/**
 * busy 发送气泡菜单（#356 语义反转，对齐 DSH web 提交策略）：
 * 立即发送（steer——注入进行中轮次，受理即上屏+排队徽标）/
 * 消息排队（queue——入服务端队列轮末派发，上屏+徽标+QueueSheet 列表；
 * 带附件置灰——队列编辑动词 text-only 契约，附件消息请立即发送）。
 * [showQueueOption]=false（V1 无队列域）时仅显示立即发送。
 */
@Composable
private fun BusySendMenuPopup(
    hasAttachments: Boolean,
    showQueueOption: Boolean,
    onDismiss: () -> Unit,
    onSendNow: () -> Unit,
    onQueue: () -> Unit,
) {
    // #361+#348 设计还原（2026-09-08 用户三度裁决）：气泡形态（Surface+
    // 指向尾巴）嵌套列表，锚 composer **上方右对齐**——不覆盖发送按钮/输入行；
    // 键盘保持原状态（focusable=false 不夺窗口焦点）。
    // 自测锚：onSizeChanged 实测气泡高度 → offset 上移（首帧用估值消除闪烁）。
    var bubbleHeight by remember { androidx.compose.runtime.mutableIntStateOf(265) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tailHeightPx = with(density) { 8.dp.toPx() }.toInt()
    val anchorGapPx = with(density) { 6.dp.toPx() }.toInt()
    androidx.compose.ui.window.Popup(
        alignment = androidx.compose.ui.Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(
            16,
            -(bubbleHeight + tailHeightPx + anchorGapPx),
        ),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.material3.MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.onSizeChanged { bubbleHeight = it.height },
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    BusyMenuItem(
                        title = stringResource(R.string.chat_busy_menu_send_now),
                        subtitle = stringResource(R.string.chat_busy_menu_send_now_desc),
                        enabled = true,
                        onClick = onSendNow,
                    )
                    if (showQueueOption) {
                        BusyMenuItem(
                            title = stringResource(R.string.chat_busy_menu_queue),
                            subtitle = if (hasAttachments) {
                                stringResource(R.string.chat_busy_menu_queue_no_attachments)
                            } else {
                                stringResource(R.string.chat_busy_menu_queue_desc)
                            },
                            enabled = !hasAttachments,
                            onClick = onQueue,
                        )
                    }
                }
            }
            // 气泡尾巴：指向下方发送按钮（右对齐留边，同 Surface 色系）
            val tailColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .padding(end = 30.dp)
                    .size(16.dp, 8.dp),
            ) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, tailColor)
            }
        }
    }
}

@Composable
private fun BusyMenuItem(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .widthIn(max = 300.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
        )
    }
}

