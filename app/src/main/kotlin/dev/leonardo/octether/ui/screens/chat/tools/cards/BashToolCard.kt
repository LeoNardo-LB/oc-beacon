package dev.leonardo.octether.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import dev.leonardo.octether.ui.components.AmoledDefaultBorder
import dev.leonardo.octether.ui.screens.chat.tools.extractToolInput
import dev.leonardo.octether.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.octether.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.octether.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.octether.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.AlphaTokens

/**
 * Bash tool card — shows $ command + output.
 * Like WebUI: trigger = "Shell" + description, content = code block with command+output.
 */
@Composable
internal fun BashToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val command = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val cleanedOutput = output.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    val displayText = buildString {
        if (command.isNotBlank()) {
            append("$ $command")
        }
        if (cleanedOutput.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(cleanedOutput.take(5000))
        }
    }

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = command.isNotBlank() || output.isNotBlank()

    ToolCardScaffold(
        icon = if (isError) Icons.Default.Error else Icons.Default.Terminal,
        iconTint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = serverTitle ?: stringResource(R.string.tool_shell),
        copyText = displayText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = hasContent,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        val halfScreenHeight = halfScreenHeight()
        val scrollState = rememberScrollState()
        Surface(
            shape = ShapeTokens.extraSmall,
            color = toolOutputContainerColor(),
            border = if (isAmoled) AmoledDefaultBorder else null,            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = displayText,
                    style = CodeTypography.copy(color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer),
                    modifier = Modifier
                        .padding(4.dp)
                        .codeHorizontalScroll()
                )
            }
        }
    }
}
