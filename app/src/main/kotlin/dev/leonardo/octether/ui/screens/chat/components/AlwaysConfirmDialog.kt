package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.octether.R

/**
 * Secondary confirmation dialog shown when user taps "Always allow" on a permission.
 * Prevents accidental permanent approvals.
 */
@Composable
internal fun AlwaysConfirmDialog(
    toolName: String,
    directoryPattern: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.permission_always_confirm_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.permission_always_confirm_message,
                    toolName,
                    directoryPattern
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.permission_always_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}
