package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RemoveRedEye
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.util.DebugLogger
import dev.leonardo.ocbeacon.util.PathUtils
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit,
    onCopyAllContent: () -> Unit,
    onToggleRenderMode: () -> Unit,
    // Phase 3：批注回调
    onAddAnnotation: (selectedText: String, startChar: Int, endChar: Int, note: String) -> Unit,
    onDeleteAnnotation: (id: String) -> Unit,
    onUpdateAnnotation: (id: String, note: String) -> Unit,
    onSubmitAnnotations: (overallNote: String, editedNotes: Map<String, String>) -> Unit,
    // Phase 4：分页
    onLoadMoreLines: () -> Unit,
    // DIFF → SOURCE 切换，让用户可从 diff 视图进行批注
    onSwitchToSource: (() -> Unit)? = null,
    // SOURCE → DIFF 切换（从 Git 变更进入的 diff 视图切到源码后可切回）
    onSwitchToDiff: (() -> Unit)? = null
) {
    // 批注状态：(selectedText, startChar, endChar)
    var pendingAnnotation by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var detailAnnotation by remember { mutableStateOf<Annotation?>(null) }
    var showSubmitDialog by remember { mutableStateOf(false) }
    // 代码/源码视图换行开关：页面级临时状态，默认不换行（水平滚动）
    var wordWrap by rememberSaveable { mutableStateOf(false) }
    // 把批注序列化为 JSON，供 WebView 高亮渲染
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
    // Phase 2：源码滚动状态 + 用于 markdown 渲染切换的比例锚点
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
                onToggleRenderMode = toggleWithAnchor,
                wordWrap = wordWrap,
                onToggleWordWrap = { wordWrap = !wordWrap },
                annotationCount = uiState.annotations.size,
                onSubmitClick = { showSubmitDialog = true },
                onSwitchToSource = onSwitchToSource,
                onSwitchToDiff = onSwitchToDiff
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Crossfade(
                targetState = uiState.isLoading,
                animationSpec = tween(AppMotion.MEDIUM),
                label = "fileViewerLoading"
            ) { isLoading ->
                if (isLoading) {
                    LoadingState()
                } else {
                    when {
                        uiState.error != null -> ErrorState(message = uiState.error)
                        uiState.isBinary -> MessageState(
                            message = stringResource(R.string.viewer_binary_not_supported),
                            detail = uiState.mimeType?.let { stringResource(R.string.viewer_binary_mime, it) }
                        )
                        uiState.mode == FileViewerMode.DIFF -> DiffView(
                            uiState = uiState,
                            wordWrap = wordWrap,
                            onNextHunk = onNextHunk,
                            onPrevHunk = onPrevHunk
                        )
                        uiState.isEmpty -> MessageState(message = stringResource(R.string.viewer_empty_file))
                        // 源码 vs 渲染预览，带平滑淡入淡出过渡
                        // 双面板：源码和渲染视图都常驻组合，切换 = 可见性开关
                        else -> {
                            val showRender = uiState.fileType.supportsRender &&
                                uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW

                            Box(Modifier.fillMaxSize()) {
                                // ── 源码面板（CodeWebView） ── 始终存在，显示渲染时隐藏
                                if (uiState.isExtremelyLarge) {
                                    Column(Modifier.fillMaxSize()) {
                                        LargeFileWarningBanner(lineCount = uiState.totalLineCount)
                                        CodeWebView(
                                            content = uiState.content,
                                            filePath = uiState.filePath,
                                            visible = !showRender,
                                            wordWrap = wordWrap,
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
                                    wordWrap = wordWrap,
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

                                // ── 渲染面板 ── 条件组合，避免拦截触摸事件
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
        }
    }

    // Phase 3：批注输入弹层
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

    // 批注编辑弹层 — 复用 AnnotationInputSheet（底部弹层，非对话框）
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

    // Phase 3：提交对话框
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
    onToggleRenderMode: () -> Unit,
    wordWrap: Boolean = false,
    onToggleWordWrap: () -> Unit = {},
    annotationCount: Int = 0,
    onSubmitClick: () -> Unit = {},
    onSwitchToSource: (() -> Unit)? = null,
    onSwitchToDiff: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                // 同时处理 / 和 \ 分隔符来提取文件名
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
                // 副标题：相对 workspace 的路径
                val relativePath = remember(uiState.filePath, uiState.directory) {
                    val fp = uiState.filePath
                    val dir = uiState.directory
                    // 如存在 workspace 前缀则剥离，否则剥离前导 "/"
                    val full = when {
                        dir.isNotBlank() && fp.startsWith(dir) -> fp.removePrefix(dir).removePrefix("/")
                        dir.isNotBlank() && fp.contains(dir) -> fp.substringAfter(dir).removePrefix("/")
                        fp.startsWith("/") -> fp.removePrefix("/")
                        else -> fp
                    }
                    // 仅显示目录部分（不含文件名）
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
            // DIFF 模式 → "源码"图标按钮，让用户切换到可批注的源码视图
            if (uiState.mode == FileViewerMode.DIFF && onSwitchToSource != null) {
                IconButton(
                    onClick = onSwitchToSource,
                    modifier = Modifier.testTag("viewer_switch_to_source")
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = stringResource(R.string.viewer_diff_show_source)
                    )
                }
            }
            // SOURCE 模式且持有 diff 数据（仅从 Git 变更面板进入时为 true）→ "diff"图标按钮可切回
            if (uiState.mode == FileViewerMode.SOURCE && onSwitchToDiff != null && uiState.hasDiff) {
                IconButton(
                    onClick = onSwitchToDiff,
                    modifier = Modifier.testTag("viewer_switch_to_diff")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = stringResource(R.string.viewer_source_show_diff)
                    )
                }
            }
            // 多格式渲染切换（存在批注时隐藏）
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
            // 代码换行切换：diff 视图或源码视图显示（渲染预览隐藏），页面级状态，默认不换行
            if (annotationCount == 0 && (uiState.mode == FileViewerMode.DIFF || uiState.renderMode == FileViewerRenderMode.SOURCE)) {
                IconButton(
                    onClick = onToggleWordWrap,
                    modifier = Modifier.testTag("viewer_wrap_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.WrapText,
                        contentDescription = stringResource(R.string.viewer_toggle_word_wrap),
                        tint = if (wordWrap) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Phase 3：存在批注时显示提交按钮
            if (annotationCount > 0) {
                TextButton(
                    onClick = onSubmitClick,
                    modifier = Modifier.testTag("annotation_submit_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(SpacingTokens.XS.dp))
                    Text(stringResource(R.string.annotation_submit))
                }
            }
        }
    )
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        PulsingDotsIndicator()
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
    // 按批注 ID 跟踪已编辑的说明
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
