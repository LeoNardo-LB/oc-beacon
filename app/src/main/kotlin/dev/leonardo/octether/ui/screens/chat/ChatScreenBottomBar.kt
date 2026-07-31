package dev.leonardo.octether.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.PromptPart
import dev.leonardo.octether.domain.model.SessionStatus
import dev.leonardo.octether.ui.screens.chat.input.ChatAttachmentsHandler
import dev.leonardo.octether.ui.screens.chat.input.ChatInputBar
import dev.leonardo.octether.ui.screens.chat.input.ChatInputMode
import dev.leonardo.octether.ui.screens.chat.util.ImageAttachment
import dev.leonardo.octether.ui.screens.chat.util.PromptBuilder
import dev.leonardo.octether.ui.screens.chat.util.SlashCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bottom bar composable extracted from ChatScreen.
 *
 * Contains the chat input bar with all its associated logic: text editing, shell mode,
 * slash commands, file mentions, attachments, model selection, and send handling.
 *
 * Re-obtains [LocalContext], [LocalView], and [LocalClipboard] internally — these are
 * ambient values that return the same instance throughout the composition tree.
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
) {
    val context = LocalContext.current
    val view = LocalView.current
    val clipboard = LocalClipboard.current

    if (sessionMeta.sessionParentId == null && !isTerminalMode &&
        (messageState.messages.isNotEmpty() || !interaction.isLoading) && interaction.error == null
    ) {
        val modelLabel = if (modelConfig.selectedModelId != null && modelConfig.providers.isNotEmpty()) {
            val provider = modelConfig.providers.find { it.id == modelConfig.selectedProviderId }
            val model = provider?.models?.get(modelConfig.selectedModelId)
            model?.name ?: modelConfig.selectedModelId ?: ""
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
                    val shouldAutoShell = !isShellMode && newValue.text.startsWith("!")
                    val normalizedValue = if (shouldAutoShell) {
                        val stripped = newValue.text.drop(1).trimStart()
                        val newCursor = (newValue.selection.start - 1).coerceAtLeast(0)
                        TextFieldValue(
                            text = stripped,
                            selection = TextRange(newCursor.coerceAtMost(stripped.length))
                        )
                    } else {
                        newValue
                    }

                    if (shouldAutoShell) {
                        onInputModeChange(ChatInputMode.SHELL.name)
                    }

                    onInputTextChange(normalizedValue)
                    viewModel.updateDraftText(normalizedValue.text)

                    // reverseLayout=true anchors at bottom; no explicit scroll needed when typing.

                    if (isShellMode || shouldAutoShell) {
                        viewModel.clearFileSearch()
                        return@ChatInputBar
                    }
                    // Detect @query before cursor for file mention
                    val cursorPos = normalizedValue.selection.start
                    val textBefore = normalizedValue.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
                    if (atMatch != null) {
                        val query = atMatch.groupValues[1]
                        viewModel.searchFilesForMention(query)
                    } else {
                        viewModel.clearFileSearch()
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
                        val shellCommand = when {
                            isShellMode -> rawText.trim()
                            rawText.startsWith("!") -> rawText.drop(1).trimStart()
                            else -> null
                        }
                        if (shellCommand != null) {
                            if (shellCommand.isBlank()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_empty))
                                }
                                return@doSend
                            }
                            if (attachments.isNotEmpty()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_attachments_unsupported))
                                }
                                return@doSend
                            }
                            viewModel.runShellCommand(shellCommand) { ok ->
                                if (!ok) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_failed))
                                    }
                                }
                            }
                            onInputTextChange(TextFieldValue(""))
                            if (isShellMode) {
                                onInputModeChange(ChatInputMode.NORMAL.name)
                            }
                            viewModel.clearConfirmedPaths()
                            viewModel.clearFileSearch()
                            viewModel.clearDraft()
                            onForceScroll()
                            return@doSend
                        }
                        // Detect slash commands (e.g., /skillname arguments)
                        if (rawText.startsWith("/") && !rawText.startsWith("/ ") && confirmedFilePaths.isEmpty()) {
                            val parts = rawText.trim().split("\\s+".toRegex(), 2)
                            val commandName = parts[0].removePrefix("/")
                            val commandArgs = parts.getOrElse(1) { "" }
                            if (commandName.isNotBlank()) {
                                viewModel.executeCommand(commandName, commandArgs) { ok ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) context.getString(R.string.chat_command_executed, commandName)
                                            else context.getString(R.string.chat_command_failed, commandName)
                                        )
                                    }
                                }
                                onInputTextChange(TextFieldValue(""))
                                if (isShellMode) {
                                    onInputModeChange(ChatInputMode.NORMAL.name)
                                }
                                viewModel.clearConfirmedPaths()
                                viewModel.clearFileSearch()
                                viewModel.clearDraft()
                                onForceScroll()
                                return@doSend
                            }
                        }
                        // Build prompt parts: split text around confirmed @file mentions
                        val allParts = PromptBuilder.buildPromptParts(rawText, confirmedFilePaths, viewModel.getSessionDirectory())
                        // Add image attachments
                        val attachmentParts = attachments.map { att ->
                            PromptPart(
                                type = "file",
                                mime = att.mime,
                                url = att.dataUrl,
                                filename = att.filename
                            )
                        }
                        viewModel.sendMessage(allParts, attachmentParts)
                        onInputTextChange(TextFieldValue(""))
                        attachmentHandler.clearAttachments()
                        onForceScroll()
                        viewModel.clearConfirmedPaths()
                        viewModel.clearFileSearch()
                        viewModel.clearDraft()
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
                    onInputModeChange(it.name)
                    if (it == ChatInputMode.SHELL) {
                        viewModel.clearFileSearch()
                    }
                },
                isSending = interaction.isSending,
                isBusy = sessionMeta.sessionStatus is SessionStatus.Busy || sessionMeta.sessionStatus is SessionStatus.Retry,
                messages = messageState.messages,
                attachments = attachments,
                onAttach = { attachmentHandler.pickImages() },
                onRemoveAttachment = { index ->
                    if (index in attachments.indices) {
                        attachmentHandler.removeAttachment(index)
                        viewModel.removeDraftAttachment(index)
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
                onAgentSelect = { viewModel.selectAgent(it) },
                variantNames = modelConfig.variantNames,
                selectedVariant = modelConfig.selectedVariant,
                onCycleVariant = { viewModel.cycleVariant() },
                commands = modelConfig.commands,
                fileSearchResults = fileSearchResults,
                confirmedFilePaths = confirmedFilePaths,
                onFileSelected = { path ->
                    // Replace @query with @path in text
                    val cursorPos = inputText.selection.start
                    val textBefore = inputText.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
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
                    viewModel.confirmFilePath(path)
                    viewModel.clearFileSearch()
                },
                onSlashCommand = { cmd ->
                    when (cmd.name) {
                        "new" -> {
                            onNavigateToSession("")  // Empty sessionId = lazy creation
                        }
                        "compact" -> {
                            onForceScroll()
                            viewModel.compactSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                    )
                                }
                            }
                        }
                        "fork" -> {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                    }
                                }
                            }
                        }
                        "share" -> {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("url", url)))
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                    }
                                }
                            }
                        }
                        "unshare" -> {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                    )
                                }
                            }
                        }
                        "undo" -> {
                            viewModel.undoMessage { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_message_undone) else context.getString(R.string.chat_message_undo_failed)
                                    )
                                }
                            }
                        }
                        "redo" -> {
                            viewModel.redoMessage { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_message_redone) else context.getString(R.string.chat_message_redo_failed)
                                    )
                                }
                            }
                        }
                        "rename" -> {
                            onShowRenameDialog()
                        }
                        "shell" -> {
                            onInputModeChange(ChatInputMode.SHELL.name)
                        }
                        "review" -> {
                            onForceScroll()
                            viewModel.executeCommand("review") { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                    )
                                }
                            }
                        }
                        else -> {
                            onForceScroll()
                            viewModel.executeCommand(cmd.name) { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, cmd.name) else context.getString(R.string.chat_command_failed, cmd.name)
                                    )
                                }
                            }
                        }
                    }
                },
                onStop = { viewModel.abortSession() },
                restoredDraft = restoredDraft,
                onConsumeRestoredDraft = { viewModel.consumeRestoredDraft() }
            )
        }
    }
}
