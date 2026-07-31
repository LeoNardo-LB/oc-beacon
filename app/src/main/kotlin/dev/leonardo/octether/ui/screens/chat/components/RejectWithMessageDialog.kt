package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.octether.R

/**
 * Dialog to provide a rejection reason when denying a sub-agent permission.
 */
@Composable
internal fun RejectWithMessageDialog(
    sourceSessionTitle: String?,
    onReject: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.permission_reject_title))
        },
        text = {
            if (sourceSessionTitle != null) {
                Text(
                    text = stringResource(R.string.permission_reject_from_agent, sourceSessionTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.permission_reject_reason_label)) },
                placeholder = { Text(stringResource(R.string.permission_reject_reason_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3
            )
        },
        confirmButton = {
            TextButton(onClick = { onReject(reason) }) {
                Text(text = stringResource(R.string.permission_reject_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}
