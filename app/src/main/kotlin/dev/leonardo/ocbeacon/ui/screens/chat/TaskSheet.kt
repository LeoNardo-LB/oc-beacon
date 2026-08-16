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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * 任务面板——ModalBottomSheet（上拉）+ TabRow（Subagents / Shells 双 tab）。
 *
 * 对应 TUI 的 composer 上拉面板：tab 区分任务 subagent 与任务 shell 任务。
 * - Subagents：状态图标 + agent/title，点击跳转子会话
 * - Shells：Terminal 图标 + 命令，点击查看输出详情（Sheet 内嵌）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(
    state: TaskUiState,
    onDismiss: () -> Unit,
    onOpenSubSession: (String) -> Unit = {},
    onRemoveShell: (String) -> Unit = {},
    shellOutputProvider: (ShellJob) -> String? = { null },
    /** 是否显示"运行中/历史"切换（V1 下 false：隐藏切换、显示全部——V1 无后台化概念） */
    showRunningFilter: Boolean = true,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedShellId by rememberSaveable { mutableStateOf<String?>(null) }
    // 2026-08-12 用户要求：进行中/历史统一切换（标题栏 SegmentedButton）
    var showHistory by rememberSaveable { mutableStateOf(false) }

    // 2026-08-12：按视图过滤——进行中 = isRunning；历史 = 已完成（含失败）
    // 2026-08-13：V1 下无后台化概念，Running/History 区分无意义——
    // showRunningFilter=false 时不过滤，直接显示全部（state.subagents / state.shells）
    val visibleSubagents = if (showRunningFilter) {
        if (showHistory) state.subagents.filter { !it.isRunning }
        else state.subagents.filter { it.isRunning }
    } else state.subagents
    val visibleShells = if (showRunningFilter) {
        if (showHistory) state.shells.filter { !it.isRunning }
        else state.shells.filter { it.isRunning }
    } else state.shells

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
                // 2026-08-16（用户决策）：只留 75% 上限（去 30% 下限，内容自然收缩）。
                // 排查备注：曾怀疑短 sheet 拖拽手势吞点击——已证伪（模拟器长时间
                // 运行后输入系统劣化导致的假象，重启后正常；非 App 代码问题）。
                .heightIn(
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
                    text = stringResource(R.string.task_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 2026-08-12 用户要求（第四次修正）：切换改为文字显示当前模式——
                // 进行中视图显示"运行中"、历史视图显示"历史"（图标无法直观体现
                // 当前页面状态）。点击文字切换；角标（右上角）显示进行中任务数。
                BadgedBox(
                    badge = {
                        if (state.badgeCount > 0) {
                            Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                                Text(
                                    text = state.badgeCount.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                ) {
                    if (showRunningFilter) {
                        TextButton(onClick = { showHistory = !showHistory }) {
                            Text(
                                text = stringResource(
                                    if (showHistory) R.string.task_sheet_history_tab
                                    else R.string.shell_status_running
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
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
                                stringResource(R.string.task_sheet_subagents_tab) +
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
                                stringResource(R.string.task_sheet_shells_tab) +
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
                            item { EmptyHint(stringResource(R.string.task_sheet_empty_subagents)) }
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
                                            // 2026-08-13：前台/后台执行标记（主会话 busy +
                                            // 子代理运行中 = 前台阻塞；转后台后主会话 idle = 后台）
                                            CompactTag(
                                                text = stringResource(
                                                    if (sub.isForeground) R.string.task_foreground else R.string.task_background
                                                ),
                                                containerColor = if (sub.isForeground) {
                                                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.FAINT)
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                                                },
                                                contentColor = if (sub.isForeground) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                            sub.agent?.takeIf { it.isNotBlank() }?.let { agent ->
                                                // 2026-08-12：与输入组件同款紧凑标签（Chip 偏大已撤）
                                                val tagColor = agentColor(agent, emptyList())
                                                AgentTag(agent = agent, tagColor = tagColor)
                                            }
                                            sub.startedAt?.let { ms ->
                                                Text(
                                                    text = DateFormatters.timeOnly().format(Date(ms)),
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
                                        // 2026-08-16（用户反馈修复）：时长与状态图标改为垂直排列——
                                        // 原 Row 水平排列在 trailing 空间不足时（agent 标题长）两者
                                        // 挤压重叠；现图标在上、时长在图标正下方（右对齐），
                                        // 任意宽度下都不重叠。
                                        Column(
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            // 统一状态图标系统（TaskStatusIcon）：进行中=转圈 / 完成=CheckCircle 绿
                                            TaskStatusIcon(
                                                status = if (running) TaskStatus.RUNNING else TaskStatus.SUCCESS,
                                                contentDescription = if (running) null else stringResource(R.string.task_sheet_subagent_completed)
                                            )
                                            // 2026-08-16（#145 执行时长）：运行中 now-startedAt 走时
                                            //（面板可见期间 1s tick）；完成态显示 durationMs
                                            //（updated-created 近似）。
                                            val elapsed = if (running) {
                                                val now by produceState(System.currentTimeMillis()) {
                                                    while (true) {
                                                        kotlinx.coroutines.delay(1_000)
                                                        value = System.currentTimeMillis()
                                                    }
                                                }
                                                sub.startedAt?.let { now - it }
                                            } else {
                                                sub.durationMs
                                            }
                                            elapsed?.let { ms ->
                                                Text(
                                                    text = formatTaskDuration(ms),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = AlphaTokens.MUTED
                                                    )
                                                )
                                            }
                                        }
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
                            item { EmptyHint(stringResource(R.string.task_sheet_empty_shells)) }
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
                                                    text = DateFormatters.timeOnly().format(Date(ms)),
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

/**
 * 2026-08-16（#145）：任务执行时长格式化——<60s 显示秒；<1h 显示 m:ss；
 * ≥1h 显示 h:mm:ss。面板 trailing 右对齐展示。
 */
internal fun formatTaskDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        m > 0 -> "%d:%02d".format(m, s)
        else -> "${s}s"
    }
}
