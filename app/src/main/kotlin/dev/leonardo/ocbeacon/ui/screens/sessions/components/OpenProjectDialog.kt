package dev.leonardo.ocbeacon.ui.screens.sessions.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionListViewModel
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 用于打开项目的目录浏览器对话框。
 * 标准文件系统浏览器，点击进入子目录。
 *
 * 所有路径操作都使用 [DirectoryPath] — 不做原始字符串切片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenProjectDialog(
    viewModel: SessionListViewModel,
    projects: List<dev.leonardo.ocbeacon.domain.model.Project>,
    initialDirectory: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val fieldColors = if (isAmoled) {
        amoledOutlinedTextFieldColors()
    } else {
        OutlinedTextFieldDefaults.colors()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── State ────────────────────────────────────────────────────────
    var currentPath by rememberSaveable { mutableStateOf<DirectoryPath?>(null) }
    var homeDir by remember { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    // #115（D2-L25）：新建文件夹输入 saveable
    var newFolderName by rememberSaveable { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var createFolderError by remember { mutableStateOf<String?>(null) }

    // ── 初始化：加载服务器路径 ──────────────────────────────────────
    LaunchedEffect(Unit) {
        try {
            val paths = viewModel.getServerPaths()
            homeDir = paths.home
            if (currentPath == null) {
                // 初始路径优先级：initialDirectory → 服务器 home 目录 → 平台根
                // （2026-08-13：V2 /api/fs/list 对 path=/ 返回 500，且服务器 home 更友好——
                //  home 为空时才回退 unixRoot/windowsDrivesRoot）
                currentPath = initialDirectory?.let { DirectoryPath.forPath(it) }
                    ?: homeDir?.takeIf { it.isNotBlank() }?.let { DirectoryPath.forPath(it) }
                    ?: if (viewModel.isWindowsServer) DirectoryPath.windowsDrivesRoot
                    else DirectoryPath.unixRoot
            }
        } catch (_: Exception) {
            if (currentPath == null) {
                currentPath = initialDirectory?.let { DirectoryPath.forPath(it) }
                    ?: DirectoryPath.unixRoot
            }
        }
    }

    // ── 响应路径变化 ────────────────────────────────────────
    LaunchedEffect(Unit) {
        snapshotFlow { currentPath }.collectLatest { path ->
            if (path == null) return@collectLatest
            isLoading = true
            directories = emptyList()
            try {
                if (path.isDrivesRoot) {
                    // 盘符列表：边探测边显示（先看到 C:/D: 等常用盘符，无需等最慢请求）
                    viewModel.listWindowsDrives().collect { node ->
                        directories = (directories + node).sortedBy { it.name }
                    }
                } else {
                    directories = viewModel.listDirectories(path.rawPath)
                }
            } catch (_: Exception) {
                directories = emptyList()
            }
            isLoading = false
        }
    }

    val params = amoledDialogParams()

    // ── Dialog ───────────────────────────────────────────────────────
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
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.sessions_open_project),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))

                // ── 路径栏 ─────────────────────────────────────────
                val path = currentPath
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canGoBack = path != null && !path.isRoot
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier
                            .size(16.dp)
                            .then(
                                if (canGoBack) Modifier.clickable {
                                    currentPath = path.parent() ?: currentPath
                                } else Modifier
                            ),
                        tint = if (canGoBack) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = path?.display(homeDir) ?: "/",
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        softWrap = false,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 目录列表 ───────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                ) {
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PulsingDotsIndicator(dotSize = 10.dp, dotSpacing = 6.dp)
                            }
                        }
                        directories.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.sessions_empty_directory),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = AlphaTokens.MUTED
                                    ),
                                )
                            }
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(
                                    items = directories,
                                    key = { it.absolute }
                                ) { node ->
                                    // absolute 非空（FileNode.absolute: String）——直接使用，
                                    // 原 fallback 链（currentPath.child / forPath(node.name)）为死代码。
                                    val targetPath = DirectoryPath.forPath(node.absolute)

                                    DirectoryRow(
                                        displayPath = node.name,
                                        onClick = { currentPath = targetPath },
                                        onNavigate = { currentPath = targetPath },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(
                            stringResource(R.string.sessions_create_session),
                            DialogButtonRole.Primary,
                        ) {
                            currentPath?.let { onSelect(it.rawPath) }
                        },
                        Triple(
                            stringResource(R.string.sessions_create_folder),
                            DialogButtonRole.Secondary,
                        ) {
                            showCreateFolderDialog = true
                            createFolderError = null
                            if (newFolderName.isBlank()) newFolderName = ""
                        },
                    )
                )
            }
        }
    }

    // ── 新建文件夹对话框 ─────────────────────────────────────────
    if (showCreateFolderDialog) {
        val createFolderParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = createFolderParams.containerColor,
                tonalElevation = createFolderParams.tonalElevation,
                border = createFolderParams.border,
                shape = createFolderParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.sessions_create_folder_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createFolderError = null
                        },
                        singleLine = true,
                        enabled = !isCreatingFolder,
                        label = { Text(stringResource(R.string.sessions_create_folder_name_label)) },
                        placeholder = {
                            Text(stringResource(R.string.sessions_create_folder_name_placeholder))
                        },
                        isError = createFolderError != null,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (createFolderError != null) {
                        Text(
                            text = createFolderError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
                                showCreateFolderDialog = false
                            },
                            Triple(
                                stringResource(R.string.sessions_create_folder_create),
                                DialogButtonRole.Primary,
                            ) {
                                val parent = currentPath?.rawPath ?: homeDir ?: "/"
                                val name = newFolderName.trim()
                                if (name.isBlank()) {
                                    createFolderError = context.getString(R.string.sessions_create_folder_invalid_name)
                                    return@Triple
                                }

                                isCreatingFolder = true
                                scope.launch {
                                    val result = viewModel.createDirectory(parent, name)
                                    isCreatingFolder = false
                                    result.onSuccess { createdPath ->
                                        showCreateFolderDialog = false
                                        newFolderName = ""
                                        createFolderError = null
                                        // 强制重新加载当前目录
                                        val reloadPath = currentPath
                                        currentPath = null
                                        currentPath = reloadPath
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(
                                                    R.string.sessions_create_folder_success,
                                                    DirectoryPath.forPath(createdPath)
                                                        .display(homeDir)
                                                ),
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }.onFailure { error ->
                                        createFolderError = error.message
                                            ?: context.getString(R.string.sessions_create_folder_failed)
                                    }
                                }
                            },
                        )
                    )
                }
            }
        }
    }
}
