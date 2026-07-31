package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic

private enum class PreviewMode { SOURCE, RENDERED }

/**
 * Full-screen dialog for viewing and copying a single assistant message.
 * Supports source (monospace, selectable) and rendered (Markdown) views.
 * No nested scroll conflicts: the dialog is a standalone screen,
 * not embedded inside a LazyColumn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownPreviewDialog(
    markdown: String,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit
) {
    // Defensive: dismiss if content is blank
    if (markdown.isBlank()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var previewMode by rememberSaveable { mutableStateOf(PreviewMode.SOURCE) }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val view = LocalView.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // TopAppBar
                TopAppBar(
                    title = { Text(stringResource(R.string.markdown_preview_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.markdown_preview_back)
                            )
                        }
                    },
                    actions = {
                        // View toggle button
                        TextButton(onClick = {
                            previewMode = if (previewMode == PreviewMode.SOURCE)
                                PreviewMode.RENDERED else PreviewMode.SOURCE
                        }) {
                            Text(
                                if (previewMode == PreviewMode.SOURCE) "渲染" else "源码"
                            )
                        }
                        // Copy all button
                        IconButton(onClick = {
                            performHaptic(view, true)
                            onCopyAll()
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制全部"
                            )
                        }
                    }
                )

                // Content area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .animateContentSize()
                ) {
                    when (previewMode) {
                        PreviewMode.SOURCE -> {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = markdown,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                        PreviewMode.RENDERED -> {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(16.dp)
                                ) {
                                    MarkdownContent(
                                        markdown = markdown,
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        isUser = false
                                    )
                                }
                            }
                        }
                    }
                }

                // Snackbar for copy confirmation
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            }
        }
    }
}
