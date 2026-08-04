package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ModelPickerDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.RenameSessionDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.SendConfirmDialog

/**
 * 渲染 ChatScreen 上显示的三个条件对话框：
 * ModelPicker、RenameSession 和 SendConfirm。
 *
 * 从 ChatScreen 提取以降低其复杂度。
 */
@Composable
internal fun ChatScreenDialogs(
    showModelPicker: Boolean,
    onDismissModelPicker: () -> Unit,
    showRenameDialog: Boolean,
    onDismissRenameDialog: () -> Unit,
    showSendConfirmDialog: Boolean,
    onConfirmSend: () -> Unit,
    onDismissSendConfirm: () -> Unit,
    providers: List<ProviderCatalog>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelectModel: (String, String) -> Unit,
    sessionTitle: String,
    onRename: (String) -> Unit,
) {
    // 模型选择对话框
    if (showModelPicker) {
        ModelPickerDialog(
            providers = providers,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            onSelect = onSelectModel,
            onDismiss = onDismissModelPicker
        )
    }

    // 重命名对话框
    if (showRenameDialog) {
        RenameSessionDialog(
            initialTitle = sessionTitle,
            onRename = onRename,
            onDismiss = onDismissRenameDialog
        )
    }

    // 发送确认对话框
    if (showSendConfirmDialog) {
        SendConfirmDialog(
            onConfirm = onConfirmSend,
            onDismiss = onDismissSendConfirm
        )
    }
}
