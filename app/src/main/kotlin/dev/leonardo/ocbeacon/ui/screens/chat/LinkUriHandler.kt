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
 * Creates a custom [UriHandler] that intercepts markdown link clicks.
 *
 * - Web links (http/https) → open browser via [Intent.ACTION_VIEW]
 * - Relative file paths → [onOpenFile] with resolved absolute path
 * - Relative directory paths → [onOpenDirectory] with resolved absolute path
 * - Absolute file paths → [onOpenFile]
 * - Absolute directory paths → Snackbar (only files supported)
 *
 * @param directory Session working directory for resolving relative paths
 * @param onOpenFile Callback to open a file (resolved path passed to FileViewerNav)
 * @param onOpenDirectory Callback to open a directory in the workspace tree
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
 * Heuristic: a path is likely a directory if it ends with a separator
 * or its last segment has no dot (no file extension).
 *
 * Known limitation: extensionless files (Makefile, Dockerfile, LICENSE)
 * are misclassified as directories. This is acceptable for v1 — the user
 * approved the heuristic approach over API pre-checks.
 */
private fun isLikelyDirectory(path: String): Boolean {
    if (path.endsWith("/") || path.endsWith("\\")) return true
    val fileName = PathUtils.fileName(path)
    return !fileName.contains(".")
}
