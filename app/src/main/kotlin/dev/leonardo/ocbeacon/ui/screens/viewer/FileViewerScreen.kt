package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.util.DebugLogger
import dev.leonardo.ocbeacon.util.PathUtils
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onCopyAllContent: () -> Unit,
    onToggleRenderMode: () -> Unit,
    // Phase 3: Annotation callbacks
    onAddAnnotation: (selectedText: String, startChar: Int, endChar: Int, note: String) -> Unit,
    onDeleteAnnotation: (id: String) -> Unit,
    onUpdateAnnotation: (id: String, note: String) -> Unit,
    onSubmitAnnotations: (overallNote: String, editedNotes: Map<String, String>) -> Unit,
    // Phase 4: pagination
    onLoadMoreLines: () -> Unit,
    // DIFF → SOURCE switch so users can annotate from diff view
    onSwitchToSource: (() -> Unit)? = null
) {
    // Annotation state: (selectedText, startChar, endChar)
    var pendingAnnotation by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var detailAnnotation by remember { mutableStateOf<Annotation?>(null) }
    var showSubmitDialog by remember { mutableStateOf(false) }
    // Serialize annotations for WebView highlight rendering
    val annotationsJson = remember(uiState.annotations) {
        if (uiState.annotations.isEmpty()) ""
        else org.json.JSONArray().apply {
            uiState.annotations.forEach { ann ->
                put(org.json.JSONObject().apply {
                    put("text", ann.selectedText)
                    put("note", ann.note)
                    put("index", ann.index)
                })
            }
        }.toString()
    }
    // Phase 2: source scroll state + fraction anchor for md render toggle
    val sourceLazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var lastSourceFraction by remember { mutableStateOf(0f) }
    val sourceLineCount = remember(uiState.content) {
        if (uiState.content.isEmpty()) 1
        else uiState.content.count { it == '\n' } + 1
    }

    val toggleWithAnchor: () -> Unit = {
        if (uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.SOURCE) {
            lastSourceFraction = if (sourceLineCount > 0 && sourceLazyListState.layoutInfo.totalItemsCount > 0) {
                sourceLazyListState.firstVisibleItemIndex.toFloat() / sourceLineCount
            } else 0f
        }
        onToggleRenderMode()
    }

    Scaffold(
        topBar = {
            FileViewerTopBar(
                uiState = uiState,
                onBack = onBack,
                onCopyPath = onCopyPath,
                onShare = onShare,
                onToggleRenderMode = toggleWithAnchor,
                annotationCount = uiState.annotations.size,
                onSubmitClick = { showSubmitDialog = true },
                onSwitchToSource = onSwitchToSource
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.error != null -> ErrorState(message = uiState.error)
                uiState.isBinary -> MessageState(
                    message = stringResource(R.string.viewer_binary_not_supported),
                    detail = uiState.mimeType?.let { stringResource(R.string.viewer_binary_mime, it) }
                )
                uiState.mode == FileViewerMode.DIFF -> DiffView(
                    uiState = uiState,
                    onNextHunk = onNextHunk,
                    onPrevHunk = onPrevHunk
                )
                uiState.isEmpty -> MessageState(message = stringResource(R.string.viewer_empty_file))
                // Source vs render preview with smooth crossfade transition
                // Dual-pane: both source and render views always composed, toggle = visibility switch
                else -> {
                    val showRender = uiState.fileType.supportsRender &&
                        uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW

                    Box(Modifier.fillMaxSize()) {
                        // ── Source pane (CodeWebView) ── always present, hidden when showing render
                        if (uiState.isExtremelyLarge) {
                            Column(Modifier.fillMaxSize()) {
                                LargeFileWarningBanner(lineCount = uiState.totalLineCount)
                                CodeWebView(
                                    content = uiState.content,
                                    filePath = uiState.filePath,
                                    visible = !showRender,
                                    onAnnotate = { text, start, end -> pendingAnnotation = Triple(text, start, end) },
                                    annotationsJson = annotationsJson,
                                    onLoadMore = if (!uiState.isFullyLoaded) onLoadMoreLines else null,
                                    onAnnotationClick = { idStr ->
                                        val idx = idStr.toIntOrNull()
                                        DebugLogger.log("FileViewer", "onAnnotationClick: idStr='$idStr', idx=$idx, annIndices=${uiState.annotations.map { it.index }}")
                                        detailAnnotation = uiState.annotations.find { it.index == idx }
                                    },
                                )
                            }
                        } else CodeWebView(
                            content = uiState.content,
                            filePath = uiState.filePath,
                            visible = !showRender,
                            onAnnotate = { text, start, end -> pendingAnnotation = Triple(text, start, end) },
                            annotationsJson = annotationsJson,
                            onLoadMore = if (!uiState.isFullyLoaded) onLoadMoreLines else null,
                            onAnnotationClick = { idStr ->
                                val idx = idStr.toIntOrNull()
                                DebugLogger.log("FileViewer", "onAnnotationClick: idStr='$idStr', idx=$idx, annIndices=${uiState.annotations.map { it.index }}")
                                detailAnnotation = uiState.annotations.find { it.index == idx }
                            },
                            initialScrollLine = uiState.initialScrollLine,
                        )

                        // ── Render pane ── conditionally composed to avoid touch interception
                        if (showRender && uiState.fileType.supportsRender) {
                            when (uiState.fileType) {
                                FileType.MARKDOWN -> RenderWebView(
                                    content = uiState.content,
                                    fileType = FileType.MARKDOWN,
                                    visible = true
                                )
                                FileType.IMAGE, FileType.SVG, FileType.CSV -> RenderWebView(
                                    content = uiState.content,
                                    fileType = uiState.fileType,
                                    mimeType = uiState.mimeType ?: "image/*",
                                    visible = true
                                )
                                FileType.HTML -> RenderWebView(
                                    content = uiState.content,
                                    fileType = FileType.HTML,
                                    visible = true
                                )
                                FileType.PDF -> PdfViewer(
                                    base64Data = uiState.content,
                                    visible = true
                                )
                                else -> {} // no-op
                            }
                        }
                    }
                }
            }
        }
    }

    // Phase 3: Annotation Input Sheet
    pendingAnnotation?.let { (selectedText, startChar, endChar) ->
        AnnotationInputSheet(
            selectedText = selectedText,
            onConfirm = { note ->
                onAddAnnotation(selectedText, startChar, endChar, note)
                pendingAnnotation = null
            },
            onDismiss = { pendingAnnotation = null }
        )
    }

    // Annotation edit sheet — reuses AnnotationInputSheet (bottom sheet, not dialog)
    detailAnnotation?.let { ann ->
        AnnotationInputSheet(
            selectedText = ann.selectedText,
            initialNote = ann.note,
            onConfirm = { newNote ->
                onUpdateAnnotation(ann.id, newNote)
                detailAnnotation = null
            },
            onDelete = {
                onDeleteAnnotation(ann.id)
                detailAnnotation = null
            },
            onDismiss = { detailAnnotation = null }
        )
    }

    // Phase 3: Submit Dialog
    if (showSubmitDialog && uiState.annotations.isNotEmpty()) {
        AnnotationSubmitDialog(
            annotationCount = uiState.annotations.size,
            annotations = uiState.annotations,
            onSubmit = { overallNote, editedNotes ->
                onSubmitAnnotations(overallNote, editedNotes)
                showSubmitDialog = false
            },
            onDismiss = { showSubmitDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerTopBar(
    uiState: FileViewerUiState,
    onBack: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onToggleRenderMode: () -> Unit,
    annotationCount: Int = 0,
    onSubmitClick: () -> Unit = {},
    onSwitchToSource: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                // Extract filename handling both / and \ separators
                val fileName = remember(uiState.filePath) { PathUtils.fileName(uiState.filePath) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (annotationCount > 0) {
                        Spacer(Modifier.width(SpacingTokens.SM.dp))
                        Badge { Text("$annotationCount") }
                    }
                }
                // Subtitle: path relative to workspace
                val relativePath = remember(uiState.filePath, uiState.directory) {
                    val fp = uiState.filePath
                    val dir = uiState.directory
                    // Strip workspace prefix if present, otherwise strip leading "/"
                    val full = when {
                        dir.isNotBlank() && fp.startsWith(dir) -> fp.removePrefix(dir).removePrefix("/")
                        dir.isNotBlank() && fp.contains(dir) -> fp.substringAfter(dir).removePrefix("/")
                        fp.startsWith("/") -> fp.removePrefix("/")
                        else -> fp
                    }
                    // Show only the directory portion (no filename)
                    PathUtils.parentDir(full).ifBlank { "" }
                }
                if (relativePath.isNotBlank()) {
                    Text(
                        text = relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        actions = {
            // DIFF mode → show "Source" button so users can switch to annotatable source view
            if (uiState.mode == FileViewerMode.DIFF && onSwitchToSource != null) {
                TextButton(
                    onClick = onSwitchToSource,
                    modifier = Modifier.testTag("viewer_switch_to_source")
                ) {
                    Text(stringResource(R.string.viewer_diff_show_source))
                }
            }
            // Multi-format render toggle (hidden when annotations exist)
            if (annotationCount == 0 && uiState.fileType.supportsRender && uiState.fileType.supportsSourceView && uiState.mode != FileViewerMode.DIFF) {
                val isRender = uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW
                IconButton(
                    onClick = onToggleRenderMode,
                    modifier = Modifier.testTag("viewer_render_button")
                ) {
                    Icon(
                        imageVector = if (isRender) Icons.Default.Description else Icons.Default.RemoveRedEye,
                        contentDescription = if (isRender) stringResource(R.string.viewer_show_source)
                        else stringResource(R.string.viewer_show_render),
                        tint = if (isRender) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Phase 3: Submit button when annotations exist
            if (annotationCount > 0) {
                TextButton(
                    onClick = onSubmitClick,
                    modifier = Modifier.testTag("annotation_submit_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(SpacingTokens.XS.dp))
                    Text(stringResource(R.string.annotation_submit))
                }
            } else {
                IconButton(onClick = onCopyPath) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.a11y_icon_copy_path)
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.a11y_icon_share)
                    )
                }
            }
        }
    )
}

/**
 * Markdown preview that scrolls to [sourceScrollFraction] of its max scroll range,
 * waiting for first non-zero maxValue (async rendering).
 */
@Composable
private fun MarkdownPreviewWithScrollAnchor(
    markdown: String,
    sourceScrollFraction: Float,
    modifier: Modifier = Modifier
) {
    val renderScrollState = rememberScrollState()
    LaunchedEffect(sourceScrollFraction) {
        snapshotFlow { renderScrollState.maxValue }
            .filter { it > 0 }
            .first()
        renderScrollState.scrollTo((renderScrollState.maxValue * sourceScrollFraction).toInt())
    }
    MarkdownPreview(
        markdown = markdown,
        scrollState = renderScrollState,
        modifier = modifier
    )
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(message),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MessageState(
    message: String,
    detail: String? = null
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TruncationBanner(loadedLines: Int, totalLines: Int) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.viewer_loading_progress, loadedLines, totalLines),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(
                horizontal = SpacingTokens.LG.dp,
                vertical = SpacingTokens.SM.dp
            )
        )
    }
}

@Composable
private fun LargeFileWarningBanner(lineCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.viewer_large_file_warning, lineCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(
                horizontal = SpacingTokens.LG.dp,
                vertical = SpacingTokens.SM.dp
            )
        )
    }
}

@Composable
private fun AnnotationSubmitDialog(
    annotationCount: Int,
    annotations: List<Annotation>,
    onSubmit: (overallNote: String, editedNotes: Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var overallNote by remember { mutableStateOf("") }
    // Track edited notes by annotation ID
    val editedNotes = remember(annotations) { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.annotation_submit_dialog_title, annotationCount))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                OutlinedTextField(
                    value = overallNote,
                    onValueChange = { overallNote = it },
                    label = { Text(stringResource(R.string.annotation_submit_overall_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4
                )
                Text(
                    text = stringResource(R.string.annotation_submit_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                annotations.sortedBy { it.index }.forEach { ann ->
                    val currentNote = editedNotes[ann.id] ?: ann.note
                    OutlinedTextField(
                        value = currentNote,
                        onValueChange = { editedNotes[ann.id] = it },
                        label = { Text("${ann.index + 1}. ${ann.positionLabel}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1, maxLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(overallNote.trim(), editedNotes.toMap()) },
                modifier = Modifier.testTag("annotation_submit_send")
            ) { Text(stringResource(R.string.annotation_submit_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
