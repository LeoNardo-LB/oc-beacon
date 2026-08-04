package dev.leonardo.ocbeacon.ui.screens.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.LinkClassifier
import dev.leonardo.ocbeacon.domain.model.LinkTarget
import dev.leonardo.ocbeacon.util.PathUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 创建自定义 [UriHandler]，拦截 markdown 链接点击。
 *
 * - Web 链接（http/https）→ 通过 [Intent.ACTION_VIEW] 打开浏览器
 * - 相对文件路径 → [onOpenFile]（解析为绝对路径）
 * - 相对目录路径 → [onOpenDirectory]（解析为绝对路径）
 * - 绝对文件路径 → [onOpenFile]
 * - 绝对目录路径 → Snackbar（仅支持文件）
 *
 * @param directory 会话工作目录，用于解析相对路径
 * @param onOpenFile 打开文件的回调（解析后的路径传递给 FileViewerNav）
 * @param onOpenDirectory 在工作区树中打开目录的回调
 */
@Composable
fun rememberLinkUriHandler(
    directory: String,
    onOpenFile: (filePath: String) -> Unit,
    onOpenDirectory: (directoryPath: String) -> Unit,
    fileChecker: suspend (filePath: String) -> Boolean,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
): UriHandler {
    val context = LocalContext.current
    val currentDirectory = rememberUpdatedState(directory)
    val currentOnOpenFile = rememberUpdatedState(onOpenFile)
    val currentOnOpenDirectory = rememberUpdatedState(onOpenDirectory)

    return remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                handleLinkClick(
                    uri = uri,
                    directory = currentDirectory.value,
                    context = context,
                    onOpenFile = currentOnOpenFile.value,
                    onOpenDirectory = currentOnOpenDirectory.value,
                    fileChecker = fileChecker,
                    snackbarHostState = snackbarHostState,
                    coroutineScope = coroutineScope,
                )
            }
        }
    }
}

private fun handleLinkClick(
    uri: String,
    directory: String,
    context: Context,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    fileChecker: suspend (String) -> Boolean,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
) {
    when (val target = LinkClassifier.classify(uri)) {
        is LinkTarget.Web -> {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_link_no_browser))
                }
            }
        }

        is LinkTarget.RelativePath -> {
            if (directory.isBlank()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_link_no_workdir))
                }
                return
            }
            val resolved = PathUtils.joinPath(directory, target.path)
            if (isLikelyDirectory(target.path)) {
                onOpenDirectory(resolved)
            } else {
                coroutineScope.launch {
                    if (fileChecker(resolved)) {
                        onOpenFile(resolved)
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_link_file_not_found))
                    }
                }
            }
        }

        is LinkTarget.AbsolutePath -> {
            if (isLikelyDirectory(target.path)) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_link_only_files))
                }
            } else {
                coroutineScope.launch {
                    if (fileChecker(target.path)) {
                        onOpenFile(target.path)
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_link_file_not_found))
                    }
                }
            }
        }
    }
}

/**
 * 启发式判断：如果路径以分隔符结尾
 * 或其最后一段没有点（无文件扩展名），则可能是目录。
 *
 * 已知限制：无扩展名文件（Makefile、Dockerfile、LICENSE）
 * 会被误判为目录。这在 v1 中可接受 —— 用户
 * 批准了启发式方法而非 API 预检查。
 */
private fun isLikelyDirectory(path: String): Boolean {
    if (path.endsWith("/") || path.endsWith("\\")) return true
    val fileName = PathUtils.fileName(path)
    return !fileName.contains(".")
}
