package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskStatus
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskStatusIcon
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 后台活动面板——ModalBottomSheet（上拉）+ TabRow（Subagents / Shells 双 tab）。
 *
 * 对应 TUI 的 composer 上拉面板：tab 区分后台 subagent 与后台 shell 任务。
 * - Subagents：状态图标 + agent/title，点击跳转子会话
 * - Shells：Terminal 图标 + 命令，点击查看输出详情（Sheet 内嵌）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(
    state: BackgroundUiState,
    onDismiss: () -> Unit,
    onOpenSubSession: (String) -> Unit = {},
    onRemoveShell: (String) -> Unit = {},
    shellOutputProvider: (ShellJob) -> String? = { null },
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedShellId by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 详情视图（点击 shell 项后内嵌展示）
        val selectedShell = selectedShellId?.let { id -> state.shells.firstOrNull { it.id == id } }
        if (selectedShell != null) {
            ShellDetailView(
                shell = selectedShell,
                output = shellOutputProvider(selectedShell),
                onClose = { selectedShellId = null }
            )
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // 2026-08-12 用户要求：去掉 "Background" 标题区域
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    // 2026-08-12 用户要求：图标与文字同一行（icon+text 都放 text 槽位，Row 排列）
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountTree,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.background_sheet_subagents_tab) +
                                    " (${state.subagents.size})"
                            )
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    // 2026-08-12 用户要求：图标与文字同一行
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.background_sheet_shells_tab) +
                                    " (${state.shells.size})"
                            )
                        }
                    }
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                when (selectedTab) {
                    0 -> {
                        if (state.subagents.isEmpty()) {
                            item { EmptyHint(stringResource(R.string.background_sheet_empty_subagents)) }
                        } else {
                            items(state.subagents, key = { it.sessionId }) { sub ->
                                val running = sub.isRunning
                                ListItem(
                                    headlineContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            // 2026-08-12 用户要求：左对齐展示子代理类型 + 标题
                                            sub.agent?.takeIf { it.isNotBlank() }?.let { agent ->
                                                Text(
                                                    text = agent.replaceFirstChar { it.uppercase() },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1
                                                )
                                            }
                                            Text(
                                                text = sub.title ?: sub.sessionId,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    supportingContent = sub.description?.let { desc ->
                                        { Text(desc, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    },
                                    // 2026-08-12 用户要求：左对齐最左边不需要图标（leading 移除）
                                    trailingContent = {
                                        // 统一状态图标系统（TaskStatusIcon）：进行中=转圈 / 完成=CheckCircle 绿
                                        TaskStatusIcon(
                                            status = if (running) TaskStatus.RUNNING else TaskStatus.SUCCESS,
                                            contentDescription = if (running) null else stringResource(R.string.background_sheet_subagent_completed)
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        onOpenSubSession(sub.sessionId)
                                    }
                                )
                            }
                        }
                    }
                    1 -> {
                        if (state.shells.isEmpty()) {
                            item { EmptyHint(stringResource(R.string.background_sheet_empty_shells)) }
                        } else {
                            items(state.shells, key = { it.id }) { shell ->
                                val running = shell.isRunning
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            text = shell.command,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    supportingContent = {
                                        // 2026-08-12 用户要求：左边展示命令上下文（cwd）
                                        Text(
                                            text = shell.cwd.takeIf { it.isNotBlank() } ?: " ",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                        )
                                    },
                                    // 2026-08-12 用户要求：左对齐最左边不需要图标（leading 移除）
                                    trailingContent = {
                                        // 统一状态图标系统（TaskStatusIcon）：进行中/成功/异常（不用文字）
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            TaskStatusIcon(
                                                status = when {
                                                    running -> TaskStatus.RUNNING
                                                    shell.exit != null && shell.exit != 0 -> TaskStatus.ERROR
                                                    else -> TaskStatus.SUCCESS
                                                },
                                                contentDescription = if (running) null else {
                                                    stringResource(
                                                        if (shell.exit != null && shell.exit != 0) R.string.shell_status_exit else R.string.shell_status_done,
                                                        shell.exit ?: 0
                                                    )
                                                }
                                            )
                                            IconButton(
                                                onClick = { onRemoveShell(shell.id) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.shell_kill),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { selectedShellId = shell.id }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun ShellDetailView(
    shell: ShellJob,
    output: String?,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = shell.command,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.shell_close)
                )
            }
        }
        Text(
            text = buildString {
                append(if (shell.isRunning) stringResource(R.string.shell_status_running) else "")
                shell.exit?.let { append(" · ").append(stringResource(R.string.shell_status_exit, it)) }
                shell.cwd.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val text = output ?: shell.output ?: ""
        Text(
            text = text.ifBlank { stringResource(R.string.shell_no_output) },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(max = 360.dp)
        )
    }
}
