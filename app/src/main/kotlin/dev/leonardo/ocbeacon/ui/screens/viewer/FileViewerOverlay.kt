package dev.leonardo.ocbeacon.ui.screens.viewer

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.EntryPointAccessors
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.util.copyToClipboard
import kotlinx.coroutines.launch

@Composable
fun FileViewerOverlay(
    params: FileViewerParams,
    onDismiss: () -> Unit
) {
    val overlayOwner = remember { OverlayViewModelStoreOwner() }
    DisposableEffect(overlayOwner) {
        onDispose { overlayOwner.viewModelStore.clear() }
    }

    val context = LocalContext.current
    val assistedFactory = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FileViewerEntryPoint::class.java
        ).fileViewerViewModelFactory()
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides overlayOwner) {
        val fileViewerViewModel: FileViewerViewModel = viewModel(
            factory = SimpleViewModelFactory { assistedFactory.create(params) }
        )

        FileViewerDialogContent(
            viewModel = fileViewerViewModel,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun FileViewerDialogContent(
    viewModel: FileViewerViewModel,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val clipboard = LocalClipboard.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var isSubmitting by remember { mutableStateOf(false) }
        // #106 lint 清偿：文案 hoist stringResource（Toast/Snackbar lambda 内不可调用）
        val copiedToClipboardMsg = stringResource(R.string.menu_copied_to_clipboard)
        val annotationSubmittedToast = stringResource(R.string.annotation_submitted_toast)
        val annotationSubmitFailedMsg = stringResource(R.string.annotation_submit_failed)

        FileViewerScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            onBack = onDismiss,
            onNextHunk = viewModel::nextHunk,
            onPrevHunk = viewModel::prevHunk,
            onCopyAllContent = {
                scope.launch {
                    clipboard.copyToClipboard("content", uiState.content)
                    snackbarHostState.showSnackbar(copiedToClipboardMsg)
                }
            },
            onToggleRenderMode = viewModel::toggleRenderMode,
            onSwitchToSource = viewModel::switchToSource,
            onSwitchToDiff = viewModel::switchToDiff,
            onAddAnnotation = { selectedText, startChar, endChar, note ->
                if (startChar >= 0) {
                    viewModel.addAnnotation(selectedText, startChar, endChar, note)
                }
            },
            onDeleteAnnotation = viewModel::deleteAnnotation,
            onUpdateAnnotation = viewModel::updateAnnotation,
            onLoadMoreLines = viewModel::loadMoreLines,
            onSubmitAnnotations = { overallNote, editedNotes ->
                if (!isSubmitting) {
                    isSubmitting = true
                    scope.launch {
                        val result = viewModel.submitAnnotations(overallNote, editedNotes)
                        isSubmitting = false
                        if (result.isSuccess) {
                            Toast.makeText(context, annotationSubmittedToast, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else {
                            snackbarHostState.showSnackbar(annotationSubmitFailedMsg)
                        }
                    }
                }
            }
        )
    }
}

private class OverlayViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

private class SimpleViewModelFactory(
    private val create: () -> ViewModel
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
