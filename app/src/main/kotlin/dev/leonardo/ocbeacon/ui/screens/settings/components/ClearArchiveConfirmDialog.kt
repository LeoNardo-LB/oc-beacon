package dev.leonardo.ocbeacon.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #271 清理全部冷存桶的二次确认对话框（不可撤销的危险操作，Danger 按钮）。
 * 结构对齐 DeleteSessionDialog（BasicAlertDialog + amoledDialogParams）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClearArchiveConfirmDialog(
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
            Column(modifier = Modifier.padding(SpacingTokens.XL.dp)) {
                Text(
                    text = stringResource(R.string.settings_storage_clear_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_storage_clear_confirm_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { onDismiss() },
                        Triple(
                            stringResource(R.string.settings_storage_clear_confirm),
                            DialogButtonRole.Danger
                        ) { onConfirm() },
                    )
                )
            }
        }
    }
}
