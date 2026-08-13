package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    sessionTitle: String,
    directory: String,
    contextDetail: ContextDetailState,
    sessionParentId: String?,
    shareUrl: String?,
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
    onNavigateBack: () -> Unit,
    onTerminalMode: () -> Unit,
    onForkSession: () -> Unit,
    onCompactSession: () -> Unit,
    /** 批量转后台（对应 TUI ctrl+b——当前会话所有前台 subagent 转为后台）。
     *  2026-08-13 用户要求：顶部菜单增加入口（转后台工具栏显示条件苛刻——
     *  仅前台任务运行时出现，用户平时找不到）。 */
    onBackgroundSession: () -> Unit,
    /** 服务器支持 share 端点时显示 Share/Unshare 菜单项（V2 当前无 share 端点，见 backlog #78）。 */
    isShareSupported: Boolean = true,
    onShare: () -> Unit,
    onUnshare: () -> Unit,
    onExport: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = sessionTitle.ifBlank { stringResource(R.string.chat_title_placeholder) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 副标题：会话工作目录（为空时隐藏）
                if (directory.isNotBlank()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            // 上下文进度指示器 —— 父会话和子会话都显示
            val showContext = contextWindow > 0 && lastContextTokens > 0
            if (showContext) {
                val percentage = Math.round(lastContextTokens.toDouble() / contextWindow * 100).toInt()
                    .coerceIn(0, 100)
                val contextColor = when {
                    percentage >= 90 -> MaterialTheme.colorScheme.error
                    percentage >= 70 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clickable { showContextDialog = true }
                ) {
                    CircularProgressIndicator(
                        progress = { percentage / 100f },
                        strokeWidth = 3.dp,
                        color = contextColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "$percentage",
                        style = MaterialTheme.typography.labelSmall,
                        color = contextColor
                    )
                }
            }

            // 上下文详情对话框 —— 父会话和子会话都显示
            if (showContextDialog) {
                ContextDetailDialog(
                    state = contextDetail,
                    onDismiss = { showContextDialog = false }
                )
            }

            // 下拉菜单 —— 仅父会话显示
            if (sessionParentId == null) {
                Box {
                    val isAmoled = isAmoledTheme()
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag("more_vert")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        border = if (isAmoled) AmoledDefaultBorder else null
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_open_workspace)) },
                            onClick = {
                                showMenu = false
                                onOpenWorkspace()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            modifier = Modifier.testTag("menu_open_workspace")
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_terminal)) },
                            onClick = {
                                showMenu = false
                                onTerminalMode()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.a11y_icon_terminal))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_fork_session)) },
                            onClick = {
                                showMenu = false
                                onForkSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CopyAll, contentDescription = stringResource(R.string.a11y_icon_copy_all))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_compact_session)) },
                            onClick = {
                                showMenu = false
                                onCompactSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Compress, contentDescription = stringResource(R.string.a11y_icon_compress))
                            }
                        )
                        // 批量转后台（2026-08-13 用户要求：入口在顶部菜单——工具栏
                        // 显示条件苛刻平时找不到；服务器无前台任务时 no-op）
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.background_toolbar_action)) },
                            onClick = {
                                showMenu = false
                                onBackgroundSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Layers, contentDescription = null)
                            }
                        )
                        // 根据当前分享状态显示分享或取消分享
                        // （V2 服务器无 share 端点时整组隐藏，见 backlog #78）
                        if (isShareSupported) {
                            if (shareUrl != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cmd_unshare)) },
                                    onClick = {
                                        showMenu = false
                                        onUnshare()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.LinkOff, contentDescription = stringResource(R.string.a11y_icon_unlink))
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_share_session)) },
                                    onClick = {
                                        showMenu = false
                                        onShare()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.a11y_icon_share))
                                    }
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export_session)) },
                            onClick = {
                                showMenu = false
                                onExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.a11y_icon_file_download))
                            }
                        )
                    }
                }
            }
        }
    )
}

