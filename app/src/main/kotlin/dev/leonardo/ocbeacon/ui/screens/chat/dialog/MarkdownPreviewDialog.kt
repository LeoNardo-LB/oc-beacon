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
 * 用于查看和复制单条 assistant 消息的全屏对话框。
 * 支持源码（等宽字体、可选）和渲染（Markdown）两种视图。
 * 无嵌套滚动冲突：该对话框是独立屏幕，
 * 不嵌入 LazyColumn 内部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownPreviewDialog(
    markdown: String,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit
) {
    // 防御性处理：内容为空时关闭
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
                        // 视图切换按钮
                        TextButton(onClick = {
                            previewMode = if (previewMode == PreviewMode.SOURCE)
                                PreviewMode.RENDERED else PreviewMode.SOURCE
                        }) {
                            Text(
                                if (previewMode == PreviewMode.SOURCE) stringResource(R.string.render)
                                else stringResource(R.string.source)
                            )
                        }
                        // 复制全部按钮
                        IconButton(onClick = {
                            performHaptic(view, true)
                            onCopyAll()
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy_all)
                            )
                        }
                    }
                )

                // 内容区域
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

                // 复制确认 Snackbar
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            }
        }
    }
}
