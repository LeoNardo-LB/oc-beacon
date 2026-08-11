package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
    onCycleVariant: () -> Unit = {},
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
    backgroundBadgeCount: Int = 0,
    onOpenBackground: () -> Unit = {},
    showBackgroundToolbar: Boolean = false,
    backgroundToolbarText: String = "",
    onBackgroundSession: () -> Unit = {},
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
    androidx.compose.runtime.LaunchedEffect(Unit) {
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
    val text = textFieldValue.text
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending && (!isShellMode || !isBusy)

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
            // 转后台工具栏——有前台 subagent 运行时从输入栏上方滑出（fade + expand 动画）。
            // 对应 TUI 的 ctrl+b：一键将当前所有前台 subagent 转为后台执行。
            AnimatedVisibility(
                visible = showBackgroundToolbar && !isShellMode,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                BackgroundToolbar(
                    text = backgroundToolbarText,
                    onBackgroundSession = onBackgroundSession,
                    onOpenBackground = onOpenBackground
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
                onCycleVariant = onCycleVariant,
                onAttach = onAttach,
                showBusy = isBusy,
                backgroundBadgeCount = backgroundBadgeCount,
                onOpenBackground = onOpenBackground,
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
                    confirmedFilePaths = confirmedFilePaths
                )

                // 发送/停止按钮——点击发送或停止，长按切换 shell 模式
                val showStop = isBusy && text.isBlank()
                SendStopButton(
                    showStop = showStop,
                    canSend = canSend,
                    isSending = isSending,
                    isShellMode = isShellMode,
                    isAmoled = isAmoled,
                    onStop = onStop,
                    onSend = onSend,
                    onInputModeChange = onInputModeChange
                )
            }
        }
    }
}
