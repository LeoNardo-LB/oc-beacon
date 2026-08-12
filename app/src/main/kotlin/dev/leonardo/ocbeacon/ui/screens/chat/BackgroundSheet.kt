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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.ui.screens.chat.components.AgentTag
import dev.leonardo.ocbeacon.ui.screens.chat.components.CompactTag
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskStatus
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskStatusIcon
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    // 2026-08-12 用户要求：进行中/历史统一切换（标题栏 SegmentedButton）
    var showHistory by rememberSaveable { mutableStateOf(false) }

    // 2026-08-12：按视图过滤——进行中 = isRunning；历史 = 已完成（含失败）
    val visibleSubagents = if (showHistory) state.subagents.filter { !it.isRunning }
        else state.subagents.filter { it.isRunning }
    val visibleShells = if (showHistory) state.shells.filter { !it.isRunning }
        else state.shells.filter { it.isRunning }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 2026-08-12 用户要求：不需要拉杆（dragHandle 为空）
        dragHandle = {}
    ) {
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
                // 2026-08-12 用户要求：面板高度 30%-75% 屏（与模型选择一致）
                .heightIn(
                    min = LocalConfiguration.current.screenHeightDp.dp * 0.3f,
                    max = LocalConfiguration.current.screenHeightDp.dp * 0.75f
                )
                .padding(bottom = 24.dp)
        ) {
            // 2026-08-12 用户要求：抽屉式组件统一标题栏（与快速导航一致）——
            // 标题 + 进行中/历史切换 + 计数 + 关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.background_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 2026-08-12 用户要求：进行中/历史统一切换（标题栏，Subagents/Shells 两 tab 共用）
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.padding(end = SpacingTokens.SM.dp),
                ) {
                    SegmentedButton(
                        selected = !showHistory,
                        onClick = { showHistory = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.shell_status_running), style = MaterialTheme.typography.labelMedium)
                    }
                    SegmentedButton(
                        selected = showHistory,
                        onClick = { showHistory = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.background_sheet_history_tab), style = MaterialTheme.typography.labelMedium)
                    }
                }
                // 2026-08-12 用户要求：标题栏显示当前进行中任务数（关闭按钮左侧）。
                // 始终显示（0 也显示——用户反馈"没看到计数"是因为无任务时不渲染）。
                Text(
                    text = "${state.badgeCount} ${stringResource(R.string.shell_status_running)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.badgeCount > 0) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    modifier = Modifier.padding(end = SpacingTokens.SM.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
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
                                    " (${visibleSubagents.size})"
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
                                    " (${visibleShells.size})"
                            )
                        }
                    }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> {
                        if (visibleSubagents.isEmpty()) {
                            item { EmptyHint(stringResource(R.string.background_sheet_empty_subagents)) }
                        } else {
                            itemsIndexed(visibleSubagents, key = { _, it -> it.sessionId }) { index, sub ->
                                // 2026-08-12 用户要求：item 之间加分界线
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                    )
                                }
                                val running = sub.isRunning
                                ListItem(
                                    // 2026-08-12 用户要求：左对齐 2 行——第一行标题
                                    headlineContent = {
                                        Text(
                                            text = sub.title ?: sub.sessionId,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    // 第二行：agent 徽章（样式同 agent 回复统计栏）+ 开始时间 + 模型徽标
                                    supportingContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            sub.agent?.takeIf { it.isNotBlank() }?.let { agent ->
                                                // 2026-08-12：与输入组件同款紧凑标签（Chip 偏大已撤）
                                                val tagColor = agentColor(agent, emptyList())
                                                AgentTag(agent = agent, tagColor = tagColor)
                                            }
                                            sub.startedAt?.let { ms ->
                                                Text(
                                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                                )
                                            }
                                            sub.modelId?.takeIf { it.isNotBlank() }?.let { model ->
                                                // 2026-08-12 用户要求：模型名称也改为徽标模式
                                                CompactTag(
                                                    text = model,
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.FAINT),
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
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
                        if (visibleShells.isEmpty()) {
                            item { EmptyHint(stringResource(R.string.background_sheet_empty_shells)) }
                        } else {
                            itemsIndexed(visibleShells, key = { _, it -> it.id }) { index, shell ->
                                // 2026-08-12 用户要求：item 之间加分界线
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                    )
                                }
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
                                        // 2026-08-12 用户要求：左边第二行 = cwd + 开始时间
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            shell.cwd.takeIf { it.isNotBlank() }?.let { cwd ->
                                                Text(
                                                    text = cwd,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                                )
                                            }
                                            shell.startedAt?.let { ms ->
                                                Text(
                                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                                )
                                            }
                                        }
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
