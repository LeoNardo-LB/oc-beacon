package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ModelPickerDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.RenameSessionDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.SendConfirmDialog

/**
 * Renders the three conditional dialogs shown over ChatScreen:
 * ModelPicker, RenameSession, and SendConfirm.
 *
 * Extracted from ChatScreen to reduce its complexity.
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
    // Model picker dialog
    if (showModelPicker) {
        ModelPickerDialog(
            providers = providers,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            onSelect = onSelectModel,
            onDismiss = onDismissModelPicker
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        RenameSessionDialog(
            initialTitle = sessionTitle,
            onRename = onRename,
            onDismiss = onDismissRenameDialog
        )
    }

    // Send confirmation dialog
    if (showSendConfirmDialog) {
        SendConfirmDialog(
            onConfirm = onConfirmSend,
            onDismiss = onDismissSendConfirm
        )
    }
}
