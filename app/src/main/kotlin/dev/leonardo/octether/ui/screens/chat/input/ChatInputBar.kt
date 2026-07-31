package dev.leonardo.octether.ui.screens.chat.input

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
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.domain.model.CommandInfo
import dev.leonardo.octether.ui.screens.chat.ChatMessage
import dev.leonardo.octether.ui.screens.chat.RevertedDraftPayload
import dev.leonardo.octether.ui.screens.chat.util.ImageAttachment
import dev.leonardo.octether.ui.screens.chat.util.SlashCommand
import dev.leonardo.octether.ui.screens.chat.util.SlashCommandRegistry
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.theme.SpacingTokens


internal enum class ChatInputMode {
    NORMAL,
    SHELL
}

// BreathingCircleIndicator moved to components/BreathingCircleIndicator.kt
// FileMentionVisualTransformation moved to input/FileMentionVisualTransformation.kt

/** Rotating placeholder hints for the input bar, similar to the WebUI prompt input. */
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
    onConsumeRestoredDraft: () -> Unit = {}
) {
    // Restore draft text when a send failure occurs
    androidx.compose.runtime.LaunchedEffect(restoredDraft) {
        restoredDraft?.let { draft ->
            onTextFieldValueChange(TextFieldValue(draft.text, TextRange(draft.text.length)))
            onConsumeRestoredDraft()
        }
    }
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // Rotate placeholder hint every 4 seconds
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

    // Build merged slash commands: client commands + server commands + skills (deduplicated)
    val clientCmds = SlashCommandRegistry.clientCommands()
    val allCommands = remember(commands, clientCmds) {
        val clientNames = clientCmds.map { it.name }.toSet()
        val serverSlash = commands
            .filter { it.name !in clientNames }
            .map { SlashCommand(it.name, it.description, it.source ?: "server") }
        clientCmds + serverSlash
    }

    // Slash command suggestions
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
        // Thin divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
            thickness = 0.5.dp
        )

        // Slash command suggestions popup (scrollable, max 40% screen height)
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

        // @ file mention suggestions popup
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
            // Agent + Model + Variant + Attach selector row — small, subtle
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
                onAttach = onAttach
            )

            // Image attachment thumbnails
            ImageAttachmentRow(
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                onSaveAttachment = onSaveAttachment
            )

            ShellModeHintBanner(
                isShellMode = isShellMode,
                isAmoled = isAmoled
            )

            // Input row
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

                // Send / Stop button — tap to send or stop, long-press toggles shell mode
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
