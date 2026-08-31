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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PendingActions
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
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCount
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
    /** 服务器支持后台任务时显示 Background 菜单项（V1 无正式后台系统——
     *  仅实验性 /experimental/session/{id}/background 且需 flag，见 backlog #85）。 */
    isBackgroundSupported: Boolean = true,
    /** 服务器支持 share 端点时显示 Share/Unshare 菜单项（V2 当前无 share 端点，见 backlog #78）。 */
    isShareSupported: Boolean = true,
    /** 服务器支持 PTY 终端时显示 Terminal 菜单项（DSH 无 terminal/pty 域——
     *  2026-08-31 全量按钮走查前置修复：此前 DSH 下入口可见但点击报错，数据层兜底不佳）。 */
    isTerminalSupported: Boolean = true,
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
            // 上下文进度指示器 —— 父会话和子智能体会话都显示。
            // DSH 根治（2026-08-31）：llm.models 目录无 contextWindow（仅
            // id/name/reasoning）→ 环入口原先永不显示、token 弹窗（含子代理
            // 区）不可达。无窗口但有 token 数据时以 token 计数 chip 作入口，
            // 不伪造窗口百分比。
            val hasTokenData = lastContextTokens > 0 ||
                contextDetail.inputTokens > 0 ||
                contextDetail.subagentTokens != null
            val showContext = (contextWindow > 0 && lastContextTokens > 0) ||
                (contextWindow <= 0 && hasTokenData)
            if (showContext) {
                if (contextWindow > 0) {
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
                } else {
                    val entryTokens = lastContextTokens.takeIf { it > 0 }
                        ?: contextDetail.subagentTokens?.total?.toInt()
                        ?: contextDetail.inputTokens
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { showContextDialog = true }
                    ) {
                        Text(
                            text = formatTokenCount(entryTokens),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 上下文详情对话框 —— 父会话和子智能体会话都显示
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
                        if (isTerminalSupported) {
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
                        }
                        // 2026-08-22 用户要求：任务转后台与分叉会话对调位置（原第 5 位提前到
                        // 第 3 位），图标与输入组件任务入口统一为 PendingActions
                        // 批量转后台（2026-08-13 用户要求：入口在顶部菜单——工具栏
                        // 显示条件苛刻平时找不到；服务器无前台任务时 no-op）
                        // V1 无正式后台系统（实验性端点需 flag）→ 隐藏（backlog #85）
                        if (isBackgroundSupported) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.task_toolbar_action)) },
                                onClick = {
                                    showMenu = false
                                    onBackgroundSession()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.PendingActions, contentDescription = null)
                                }
                            )
                        }
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

