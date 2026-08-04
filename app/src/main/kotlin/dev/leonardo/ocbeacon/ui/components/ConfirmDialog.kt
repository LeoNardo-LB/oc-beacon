package dev.leonardo.ocbeacon.ui.components

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
 * 通用确认对话框，包含标题、消息和可自定义的按钮。
 *
 * 使用统一的 BasicAlertDialog + Surface + DialogButtons 模式。
 *
 * @param title           对话框标题文本。
 * @param message         对话框正文文本。
 * @param confirmLabel    确认按钮的标签。
 * @param confirmRole     确认按钮的角色（默认：Danger）。
 * @param dismissLabel    取消按钮的标签（默认：取自 android.R.string.cancel 的 "Cancel"）。
 * @param onDismiss       对话框被关闭时调用（取消或点击外部）。
 * @param onConfirm       用户点击确认按钮时调用。
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
