package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Extracts system prompt text from a list of message parts.
 * Looks for Text parts in messages with role "system" or Text parts
 * whose content starts with "System:".
 *
 * @param systemParts The list of parts from system-type messages.
 * @return The concatenated system prompt text, or null if empty.
 */
fun extractSystemPrompt(systemParts: List<String>): String? {
    val text = systemParts.filter { it.isNotBlank() }.joinToString("\n\n")
    return text.ifBlank { null }
}

/**
 * A bottom sheet dialog that displays the system prompt for the current session.
 *
 * @param systemPrompt The system prompt text to display.
 * @param onDismiss Callback when the dialog is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptDialog(
    systemPrompt: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_system_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (systemPrompt.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.chat_system_prompt_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                )
            } else {
                Text(
                    text = systemPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}
