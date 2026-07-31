package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * WebFetch tool card — shows URL + content summary.
 */
@Composable
internal fun WebFetchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val url = input["url"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running
    val isPrompt = input["prompt"]?.jsonPrimitive?.contentOrNull

    val title = if (url.isNotBlank()) {
        val shortUrl = if (url.length > 50) url.take(47) + "..." else url
        "${stringResource(R.string.tool_web_fetch)} · $shortUrl"
    } else {
        stringResource(R.string.tool_web_fetch)
    }

    ToolCardScaffold(
        icon = Icons.Default.Language,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = url,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = output.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            // URL display
            if (url.isNotBlank()) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.HIGH)
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Prompt label
            if (isPrompt != null) {
                Text(
                    text = stringResource(R.string.chat_webfetch_prompt, isPrompt),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // Content summary
            if (output.isNotBlank()) {
                val halfScreenHeight = halfScreenHeight()
                val scrollState = rememberScrollState()
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = halfScreenHeight)
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Text(
                            text = output.take(5000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isAmoled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
