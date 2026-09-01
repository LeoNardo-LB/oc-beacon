package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.JobView
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskStatus
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskStatusIcon
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.AgentSuccess
import dev.leonardo.ocbeacon.ui.theme.AgentWarning
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SheetTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 四个独立 sheet（2026-08-22 第十轮：工具栏五入口拆解——用户定案）。
 *
 * 形态统一 TaskSheet 同款：ModalBottomSheet + 75% 屏高 + 标题栏（标题+计数+关闭）。
 * 无 tab 隔离（每个 sheet 单一职责）；无数据可打开看历史（不置灰——用户 Q4）。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    LocalConfiguration.current.screenHeightDp.dp *
                        SheetTokens.ChatSheetHeightFraction
                )
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                actions()
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
            content()
        }
    }
}

/** TODO sheet：TodoList 迁自 PendingTodoDrawer（只读三态）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoSheet(
    todos: List<SseEvent.TodoUpdated.Todo>,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.pending_tab_todo_plain),
        onDismiss = onDismiss,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) { TodoList(todos = todos) }
    }
}

// ============ agent / shell sheet（内容迁自 TaskSheet，含历史） ============

/** agent sheet：多级缩进树（2026-09 树化，用户裁决双轨数据源）。
 *
 * - DSH：subagent.list 权威域——面板打开拉根层、展开未缓存层逐层懒加载，
 *   失败软降级本地镜像递归；diagnostic 行（corrupt/unsupported/unavailable）
 *   灰显不可点（官方同款）。
 * - OpenCode V2：本地 session 镜像按 parentId 递归子树（防环），全本地重算。
 *
 * 行内容 MVP（用户裁决）：状态点 + 主标签 + 深度缩进 + 展开箭头；
 * 不放 token/时长指标（归顶部 token 弹窗特性）。点行本体直达该子会话 Chat
 *（onOpenSubSession 点击链路沿既有实现）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSheet(
    state: TaskUiState,
    onDismiss: () -> Unit,
    onOpenSubSession: (String) -> Unit,
) {
    // 面板打开 → 刷新根层（DSH：subagent.list 权威拉取；OpenCode：无域，本地镜像已就绪）
    val controller = state.subagentTreeController
    LaunchedEffect(controller) { controller?.refreshRoot() }
    SheetScaffold(
        title = stringResource(R.string.toolbar_agent) + " (" + state.subagentTreeRows.count { it.depth == 0 } + ")",
        onDismiss = onDismiss,
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (state.subagentTreeRows.isEmpty()) {
                item { EmptyHint(stringResource(R.string.task_sheet_empty_subagents)) }
            } else {
                itemsIndexed(state.subagentTreeRows, key = { _, it -> it.sessionId }) { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                    AgentTreeRowItem(
                        row = row,
                        loading = row.sessionId in state.subagentTreeLoadingIds,
                        onToggle = { controller?.toggle(row.sessionId) },
                        onOpen = { onOpenSubSession(row.sessionId) },
                    )
                }
            }
        }
    }
}

/** 树行：缩进（深度×12dp）+ 展开箭头（仅有子代行，懒加载中转 spinner）+ 状态点 + 主标签。 */
@Composable
private fun AgentTreeRowItem(
    row: SubagentTreeRow,
    loading: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 深度缩进：整行内容（箭头+状态点+标签）按层级左移距
                modifier = Modifier.padding(start = (row.depth * AGENT_TREE_INDENT_STEP_DP).dp),
            ) {
                if (row.hasChildren) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = if (row.isExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                contentDescription = stringResource(
                                    if (row.isExpanded) R.string.a11y_icon_collapse else R.string.a11y_icon_expand
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
                if (row.isDiagnostic) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                } else {
                    TaskStatusIcon(
                        status = if (row.isRunning) TaskStatus.RUNNING else TaskStatus.SUCCESS,
                        contentDescription = if (row.isRunning) null
                        else stringResource(R.string.task_sheet_subagent_completed),
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = if (row.isDiagnostic) agentTreeDiagnosticLabel(row.reason) else row.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (row.isDiagnostic) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        // diagnostic 行不可点（无有效子会话可直达）；其余点行本体直达子会话 Chat
        modifier = Modifier.clickable(enabled = !row.isDiagnostic) { onOpen() },
    )
}

/** diagnostic 原因本地化（闭集三值 + 未知原串兜底）。 */
@Composable
private fun agentTreeDiagnosticLabel(reason: String?): String = when (reason) {
    "corrupt" -> stringResource(R.string.agent_tree_diagnostic_corrupt)
    "unsupported" -> stringResource(R.string.agent_tree_diagnostic_unsupported)
    "unavailable" -> stringResource(R.string.agent_tree_diagnostic_unavailable)
    else -> reason.orEmpty()
}

/** 树行缩进步长（dp）。 */
private const val AGENT_TREE_INDENT_STEP_DP = 12

/** shell sheet：TaskSheet Shells tab 内容迁移（含历史 + 详情视图）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellSheet(
    state: TaskUiState,
    onDismiss: () -> Unit,
    onRemoveShell: (String) -> Unit,
    shellOutputProvider: (ShellJob) -> String?,
) {
    // DSH 任务源分流（仓库层 serverType 门控）：DSH 会话渲染 session/jobs 快照的
    // JobView 行；V2/OpenCode 走既有 ShellJob 列表（零改动）。
    if (state.serverType == ServerType.Dsh) {
        DshJobSheet(state = state, onDismiss = onDismiss)
        return
    }
    var selectedShellId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.shells.firstOrNull { it.id == selectedShellId }
    if (selected != null) {
        ShellDetailView(shell = selected, output = shellOutputProvider(selected), onClose = { selectedShellId = null })
        return
    }
    SheetScaffold(
        title = stringResource(R.string.toolbar_shell) + " (" + state.shells.size + ")",
        onDismiss = onDismiss,
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (state.shells.isEmpty()) {
                item { EmptyHint(stringResource(R.string.task_sheet_empty_shells)) }
            } else {
                itemsIndexed(state.shells, key = { _, it -> it.id }) { index, shell ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp),
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
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        supportingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                shell.cwd.takeIf { it.isNotBlank() }?.let { cwd ->
                                    Text(
                                        text = cwd,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                    )
                                }
                                shell.startedAt?.let { ms ->
                                    Text(
                                        text = DateFormatters.timeOnly().format(Date(ms)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                                            shell.exit ?: 0,
                                        )
                                    },
                                )
                                IconButton(onClick = { onRemoveShell(shell.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.shell_kill),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { selectedShellId = shell.id },
                    )
                }
            }
        }
    }
}

// ============ DSH 后台任务面板（session/jobs 整快照 JobView 行） ============

/** DSH shell sheet：JobView 整快照列表（状态点 + kind 徽章 + label + detail + 时长）。 */
@Composable
private fun DshJobSheet(
    state: TaskUiState,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.toolbar_shell) + " (" + state.dshJobs.size + ")",
        onDismiss = onDismiss,
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (state.dshJobs.isEmpty()) {
                item { EmptyHint(stringResource(R.string.dsh_jobs_empty)) }
            } else {
                itemsIndexed(state.dshJobs, key = { _, it -> it.id }) { index, job ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                    DshJobRow(job = job)
                }
            }
        }
    }
}

/** DSH 后台任务单行：状态点（颜色语义对齐面板既有状态点）+ kind 徽章 + label（mono）。 */
@Composable
private fun DshJobRow(job: JobView) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Default.FiberManualRecord,
                contentDescription = dshJobStatusLabel(job.status),
                modifier = Modifier.size(12.dp),
                tint = dshJobStatusColor(job.status),
            )
        },
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = ShapeTokens.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = job.kind,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = job.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                job.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                }
                Text(
                    text = dshJobStatusLabel(job.status) + " · " + formatDuration(dshJobDurationMs(job)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                )
            }
        },
    )
}

/** 状态点颜色：running=primary · stopping/killed=警告 · completed=完成绿 · failed=失败红。 */
@Composable
private fun dshJobStatusColor(status: String): Color = when (status) {
    JobView.STATUS_RUNNING -> MaterialTheme.colorScheme.primary
    JobView.STATUS_STOPPING, JobView.STATUS_KILLED -> AgentWarning
    JobView.STATUS_COMPLETED -> AgentSuccess
    JobView.STATUS_FAILED -> AgentError
    else -> MaterialTheme.colorScheme.outline
}

/** 状态文案本地化（闭集五值 + 未知原串兜底）。 */
@Composable
private fun dshJobStatusLabel(status: String): String = when (status) {
    JobView.STATUS_RUNNING -> stringResource(R.string.dsh_job_status_running)
    JobView.STATUS_STOPPING -> stringResource(R.string.dsh_job_status_stopping)
    JobView.STATUS_KILLED -> stringResource(R.string.dsh_job_status_killed)
    JobView.STATUS_COMPLETED -> stringResource(R.string.dsh_job_status_completed)
    JobView.STATUS_FAILED -> stringResource(R.string.dsh_job_status_failed)
    else -> status
}

/** 时长：finishedAt 或 now（运行中）——实时走时可选，此处静态（重进面板重算）。 */
private fun dshJobDurationMs(job: JobView): Long =
    ((job.finishedAt ?: System.currentTimeMillis()) - job.startedAt).coerceAtLeast(0L)

@Composable
private fun TodoList(todos: List<SseEvent.TodoUpdated.Todo>) {
    if (todos.isEmpty()) {
        EmptyHint(stringResource(R.string.todo_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(todos, key = { _, it -> it.content + "#" + it.status }) { _, todo ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            ) {
                when (todo.status) {
                    "completed" -> Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED))
                    "in_progress" -> Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED))
                }
                Text(
                    text = todo.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (todo.status) {
                        "completed" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        "in_progress" -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                    },
                    textDecoration = if (todo.status == "cancelled") TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 空态提示。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center,
    )
}

/** 拖拽重排进行时状态。 */
private data class DragState(val index: Int, val offset: Float)

/** shell 详情视图（迁自 TaskSheet）。 */
@Composable
private fun ShellDetailView(
    shell: ShellJob,
    output: String?,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                LocalConfiguration.current.screenHeightDp.dp *
                    SheetTokens.ChatSheetHeightFraction
            )
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = shell.command,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.shell_close))
            }
        }
        Text(
            text = buildString {
                append(if (shell.isRunning) stringResource(R.string.shell_status_running) else "")
                shell.exit?.let { append(" · ").append(stringResource(R.string.shell_status_exit, it)) }
                shell.cwd.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val text = output ?: shell.output ?: ""
        Text(
            text = text.ifBlank { stringResource(R.string.shell_no_output) },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** 任务执行时长格式化（迁自 TaskSheet；<60s 秒 / <1h m:ss / ≥1h h:mm:ss）。 */
internal fun formatTaskDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        m > 0 -> String.format(Locale.getDefault(), "%d:%02d", m, s)
        else -> String.format(Locale.getDefault(), "%ds", s)
    }
}