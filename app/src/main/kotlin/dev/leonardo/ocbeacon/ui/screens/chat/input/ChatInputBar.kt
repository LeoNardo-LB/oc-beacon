package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.RevertedDraftPayload
import dev.leonardo.ocbeacon.ui.screens.chat.util.ImageAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommand
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommandRegistry
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import androidx.compose.ui.graphics.luminance


internal enum class ChatInputMode {
    NORMAL,
    SHELL
}

// BreathingCircleIndicator moved to components/BreathingCircleIndicator.kt
// FileMentionVisualTransformation moved to input/FileMentionVisualTransformation.kt

/** 输入栏的轮换占位符提示，类似 WebUI 的提示输入。 */
private val placeholderHintResIds = listOf(
    R.string.chat_hint_ask,
    R.string.chat_hint_fix,
    R.string.chat_hint_refactor,
    R.string.chat_hint_tests,
    R.string.chat_hint_explain,
    R.string.chat_hint_help,
)

@Composable
internal fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    /** 2026-08-14：等待提问/权限响应时禁用输入（用户要求"提问时输入框不可以输入"）。 */
    inputEnabled: Boolean = true,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentSelect: (String) -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    commands: List<CommandInfo> = emptyList(),
    fileSearchResults: List<String> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    onStop: () -> Unit = {},
    restoredDraft: RevertedDraftPayload? = null,
    onConsumeRestoredDraft: () -> Unit = {},
    taskBadgeCount: Int = 0,
    onOpenTaskPanel: () -> Unit = {},
    onQuickNavigate: () -> Unit = {},
    showTaskToolbar: Boolean = false,
    taskToolbarText: String = "",
    onBackgroundSession: () -> Unit = {},
    /** 堆积消息（2026-08-20 设计定稿）：busy+有内容时气泡菜单的「堆积」回调；null=不支持。 */
    onEnqueue: (() -> Unit)? = null,
    pendingBadgeCount: Int = 0,
    todoPendingCount: Int = 0,
    onOpenPendingPanel: () -> Unit = {},
) {
    // 发送失败时恢复草稿文本
    androidx.compose.runtime.LaunchedEffect(restoredDraft) {
        restoredDraft?.let { draft ->
            onTextFieldValueChange(TextFieldValue(draft.text, TextRange(draft.text.length)))
            onConsumeRestoredDraft()
        }
    }
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // 每 4 秒轮换占位符提示
    val hintIndex = remember { mutableIntStateOf(0) }
    var textFieldFocused by remember { mutableStateOf(false) }
    val text = textFieldValue.text
    // L-8：仅输入框聚焦且文本为空时轮换——原 4s 永久轮换即使无焦点也持续
    // 触发 state 写 + 重组；占位符仅在空文本时可见，空且无焦点时轮换无意义。
    val shouldRotateHint = textFieldFocused && text.isEmpty()
    androidx.compose.runtime.LaunchedEffect(shouldRotateHint) {
        if (!shouldRotateHint) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = if (isShellMode) {
        stringResource(R.string.chat_shell_placeholder)
    } else {
        stringResource(placeholderHintResIds[hintIndex.intValue])
    }
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending && (!isShellMode || !isBusy) && inputEnabled

    // 构建合并的斜杠命令：客户端命令 + 服务器命令 + 技能（去重）
    val clientCmds = SlashCommandRegistry.clientCommands()
    val allCommands = remember(commands, clientCmds) {
        val clientNames = clientCmds.map { it.name }.toSet()
        val serverSlash = commands
            .filter { it.name !in clientNames }
            .map { SlashCommand(it.name, it.description, it.source ?: "server") }
        clientCmds + serverSlash
    }

    // 斜杠命令建议
    val showSlashSuggestions = !isShellMode && text.startsWith("/") && !text.contains(" ")
    val slashQuery = if (showSlashSuggestions) text.removePrefix("/").lowercase() else ""
    val filteredCommands = if (showSlashSuggestions) {
        allCommands.filter { cmd ->
            slashQuery.isEmpty() || cmd.name.lowercase().contains(slashQuery)
        }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // 细分隔线（暗色模式下改用更亮的 outline 提升可见度）
        HorizontalDivider(
            color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MUTED)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            },
            thickness = 0.5.dp
        )

        // 斜杠命令建议弹窗（可滚动，最高 40% 屏幕高度）
        if (!isShellMode) {
            SlashCommandSuggestions(
                commands = filteredCommands,
                onSkillClick = { cmd ->
                    val skillText = "/${cmd.name} "
                    onTextFieldValueChange(TextFieldValue(skillText, TextRange(skillText.length)))
                },
                onCommandClick = { cmd ->
                    onTextFieldValueChange(TextFieldValue(""))
                    onSlashCommand(cmd)
                }
            )
        }

        // @ 文件提及建议弹窗
        if (!isShellMode) {
            FileMentionSuggestions(
                results = fileSearchResults,
                onFileSelected = onFileSelected
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SpacingTokens.LG.dp, end = SpacingTokens.LG.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
        ) {
            // 任务工具栏——有前台 subagent 运行时从输入栏上方滑出（fade + expand 动画）。
            // 对应 TUI 的 ctrl+b：一键将当前所有前台 subagent 转为后台执行。
            AnimatedVisibility(
                visible = showTaskToolbar && !isShellMode,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                TaskToolbar(
                    text = taskToolbarText,
                    onBackgroundSession = onBackgroundSession,
                    onOpenTaskPanel = onOpenTaskPanel
                )
            }

            // Agent + 模型 + 变体 + 附件选择器行——小巧、低调
            AgentModelVariantSelector(
                modelLabel = modelLabel,
                selectedProviderId = selectedProviderId,
                agents = agents,
                selectedAgent = selectedAgent,
                variantNames = variantNames,
                selectedVariant = selectedVariant,
                onModelClick = onModelClick,
                onAgentSelect = onAgentSelect,
                onAttach = onAttach,
                taskBadgeCount = taskBadgeCount,
                onOpenTaskPanel = onOpenTaskPanel,
                onQuickNavigate = onQuickNavigate,
                pendingBadgeCount = pendingBadgeCount,
                todoPendingCount = todoPendingCount,
                onOpenPendingPanel = onOpenPendingPanel,
            )

            // 图片附件缩略图
            ImageAttachmentRow(
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                onSaveAttachment = onSaveAttachment
            )

            ShellModeHintBanner(
                isShellMode = isShellMode,
                isAmoled = isAmoled
            )

            // 输入行
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
            ) {
                ChatTextField(
                    textFieldValue = textFieldValue,
                    onTextFieldValueChange = onTextFieldValueChange,
                    placeholder = placeholder,
                    isShellMode = isShellMode,
                    isAmoled = isAmoled,
                    confirmedFilePaths = confirmedFilePaths,
                    enabled = inputEnabled,
                    onFocusChange = { textFieldFocused = it }
                )

                // 发送/停止按钮——点击发送或停止，长按切换 shell 模式
                // 2026-08-17（用户需求）：会话状态表示（busy 转圈）放按钮上——
                // isBusy 且无文本时显示停止图标；isBusy 且有输入时显示转圈（点击中断）
                val showStop = isBusy && text.isBlank()
                SendStopButton(
                    showStop = showStop,
                    isBusy = isBusy,
                    canSend = canSend,
                    isSending = isSending,
                    isShellMode = isShellMode,
                    isAmoled = isAmoled,
                    hasAttachments = attachments.isNotEmpty(),
                    onStop = onStop,
                    onSend = onSend,
                    onInputModeChange = onInputModeChange,
                    onEnqueue = onEnqueue
                )
            }
        }
    }
}
