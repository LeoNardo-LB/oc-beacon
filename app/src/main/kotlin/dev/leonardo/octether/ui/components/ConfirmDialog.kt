package dev.leonardo.octether.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Generic confirmation dialog with title, message, and customizable buttons.
 *
 * Uses the unified BasicAlertDialog + Surface + DialogButtons pattern.
 *
 * @param title           Dialog title text.
 * @param message         Dialog body text.
 * @param confirmLabel    Label for the confirm button.
 * @param confirmRole     Role of the confirm button (default: Danger).
 * @param dismissLabel    Label for the dismiss button (default: "Cancel" from android.R.string.cancel).
 * @param onDismiss       Called when the dialog is dismissed (cancel or outside click).
 * @param onConfirm       Called when the confirm button is clicked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmRole: DialogButtonRole = DialogButtonRole.Danger,
    dismissLabel: String = stringResource(android.R.string.cancel),
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(dismissLabel, DialogButtonRole.Secondary, onDismiss),
                        Triple(confirmLabel, confirmRole, onConfirm),
                    )
                )
            }
        }
    }
}
