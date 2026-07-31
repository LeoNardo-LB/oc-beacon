package dev.leonardo.octether.ui.screens.chat.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.components.amoledDialogParams
import dev.leonardo.octether.ui.components.DialogButtons
import dev.leonardo.octether.ui.components.DialogButtonRole

/**
 * Dialog for renaming the current session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSessionDialog(
    initialTitle: String,
    onRename: (newTitle: String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameText by remember { mutableStateOf(initialTitle) }
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.session_rename),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_rename_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.session_rename_button), DialogButtonRole.Primary) {
                            onRename(renameText)
                            onDismiss()
                        },
                    )
                )
            }
        }
    }
}

/**
 * Confirmation dialog shown before sending a message when "confirm before send" is enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_confirm_send_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_confirm_send_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.settings_send), DialogButtonRole.Primary) {
                            onConfirm()
                            onDismiss()
                        },
                    )
                )
            }
        }
    }
}

/**
 * Confirmation dialog for reverting a compaction-trigger message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RevertCompactionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.chat_revert_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.chat_revert_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.chat_revert), DialogButtonRole.Danger) {
                            onConfirm()
                            onDismiss()
                        },
                    )
                )
            }
        }
    }
}
