package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Tag
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.DetailRow
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import dev.leonardo.ocbeacon.ui.theme.DiffAdded
import dev.leonardo.ocbeacon.ui.theme.DiffRemoved
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    item: SessionItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: (String) -> Unit = {},
    onAssignCategory: () -> Unit = {},
    /** #177：堆积队列条数（>0 时详情对话框显示「继续发送堆积消息」）。 */
    pendingCount: Int = 0,
    onContinueQueue: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    /** #276 能力位门控：DSH 无 session.delete——详情对话框删除按钮隐藏。 */
    deleteSupported: Boolean = true,
    // UI-B：Agent 预设只读标签（DSH 专属，能力位门控 + id→name 解析）
    agentPresetSupported: Boolean = false,
    agentPresetNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    showDirectory: Boolean = false,
    // #271：同步状态（长按菜单同步详情区——唯一展示面，2026-08-30 四轮定稿）
    syncState: dev.leonardo.ocbeacon.data.local.SessionSyncEntity? = null,
    onRequestSync: () -> Unit = {},
    onCancelSync: () -> Unit = {},
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val addColor = DiffAdded
    val delColor = DiffRemoved

    var showDetailsDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDetailsDialog = true },
            )
            .padding(start = if (showDirectory) SpacingTokens.MD.dp else 28.dp, end = SpacingTokens.SM.dp)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态图标（2026-08-14：Asking 待回答会话——保持原气泡图标，
        // 换填充高亮版 + primary 色，不换成问题图标）
        val (statusIcon, statusIconColor) = when (item.status) {
            is SessionStatus.Busy -> Icons.Filled.ChatBubble to MaterialTheme.colorScheme.tertiary
            is SessionStatus.Asking -> Icons.Filled.ChatBubble to MaterialTheme.colorScheme.primary
            is SessionStatus.Retry -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            else -> Icons.Outlined.ChatBubbleOutline to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
        }
        // 状态图标 + 未读 Badge（Material3 BadgedBox：badge 自动定位图标右上角）
        BadgedBox(
            badge = {
                if (item.hasUnread) {
                    val unreadLabel = stringResource(R.string.session_unread_indicator)
                    Badge(
                        modifier = Modifier.semantics { contentDescription = unreadLabel },
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = stringResource(R.string.a11y_icon_toggle_session),
                modifier = Modifier.size(20.dp),
                tint = statusIconColor,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.session.title ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyMedium,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )

            // 目录副标题（仅在最近模式下显示）
            if (showDirectory) {
                val dir = item.session.directory.replace('\\', '/').trimEnd('/')
                Text(
                    text = dir,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            Spacer(modifier = Modifier.height(1.dp))
            // 第二行（摘要行）：内容自适应高度
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateFormat.format(Date(item.session.time.updated)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )

                // 状态标签
                when (item.status) {
                    is SessionStatus.Busy -> {
                        Text(
                            text = stringResource(R.string.sessions_working),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    is SessionStatus.Asking -> {
                        // 2026-08-14：提问中并入状态枚举（原 hasPendingQuestion 独立标记移除）
                        Icon(
                            Icons.Outlined.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.session_pending_question),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is SessionStatus.Retry -> {
                        Text(
                            text = stringResource(R.string.sessions_retrying),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {}
                }

                // 草稿指示器
                if (item.hasDraft) {
                    Icon(
                        imageVector = Icons.Outlined.EditNote,
                        contentDescription = stringResource(R.string.draft),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    )
                }

                // Diff 摘要
                val summary = item.session.summary
                if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (summary.additions > 0) {
                            Text(
                                text = stringResource(R.string.session_changes_additions, summary.additions),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = addColor,
                                ),
                            )
                        }
                        if (summary.deletions > 0) {
                            Text(
                                text = stringResource(R.string.session_changes_deletions, summary.deletions),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = delColor,
                                ),
                            )
                        }
                    }
                }

                // 标签区（第三行右对齐；多标签横排，内容超出可用宽度时循环滚动播放）。
                // 容器高度自动分配（内容驱动），tag 小徽章高度跟随；无 tag 时不渲染该行。
                if (item.tags.isNotEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        TagChipsRow(tags = item.tags)
                    }
                }
            }
        }

        // 收藏图标（行尾）：点击切换收藏，不触发行点击
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.remove_favorite else R.string.favorites_title
                ),
                modifier = Modifier.size(20.dp),
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                },
            )
        }
    }

    // 带操作按钮的详情对话框
    if (showDetailsDialog) {
        val isAmoled = isAmoledTheme()
        SessionDetailsDialog(
            item = item,
            onDismiss = { showDetailsDialog = false },
            onRename = {
                showDetailsDialog = false
                onRename()
            },
            onDelete = {
                showDetailsDialog = false
                onDelete()
            },
            onCopyId = {
                onCopyId(item.session.id)
            },
            onAssignCategory = {
                showDetailsDialog = false
                onAssignCategory()
            },
            pendingCount = pendingCount,
            onContinueQueue = {
                showDetailsDialog = false
                onContinueQueue()
            },
            deleteSupported = deleteSupported,
            agentPresetSupported = agentPresetSupported,
            agentPresetNames = agentPresetNames,
            syncState = syncState,
            onRequestSync = onRequestSync,
            onCancelSync = onCancelSync,
            isAmoled = isAmoled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: () -> Unit,
    onAssignCategory: () -> Unit,
    pendingCount: Int,
    onContinueQueue: () -> Unit,
    deleteSupported: Boolean,
    // UI-B：Agent 预设只读标签（DSH 专属）
    agentPresetSupported: Boolean,
    agentPresetNames: Map<String, String>,
    // #271：同步详情区（唯一展示面）
    syncState: dev.leonardo.ocbeacon.data.local.SessionSyncEntity?,
    onRequestSync: () -> Unit,
    onCancelSync: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isAmoled: Boolean,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val params = amoledDialogParams()

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
            Column(modifier = Modifier.padding(SpacingTokens.XL.dp)) {
                Text(
                    text = stringResource(R.string.session_session_details),
                    style = MaterialTheme.typography.titleMedium,
                )
                // 标签块：标题下方独立区域，按 tag 换行动态调整高度；无 tag 时不展示
                if (item.tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                    ) {
                        item.tags.forEach { tag ->
                            TagBadge(tag)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                        DetailRow(
                            stringResource(R.string.session_details_name),
                            item.session.title ?: stringResource(R.string.session_untitled)
                        )
                        DetailRow(stringResource(R.string.session_details_id), item.session.id)
                        DetailRow(
                            stringResource(R.string.session_details_status),
                            when (item.status) {
                                is SessionStatus.Busy -> stringResource(R.string.session_status_busy)
                                is SessionStatus.Retry -> stringResource(R.string.sessions_retrying)
                                else -> stringResource(R.string.session_status_idle)
                            }
                        )
                        DetailRow(
                            stringResource(R.string.session_details_created),
                            // #276 走查 N3：DSH 会话无 created（哨兵 0）→「—」，
                            // 不渲染 1970-01-01，也不以 updated 冒充
                            dev.leonardo.ocbeacon.util.DateFormatters.formatEpochOrDash(
                                dateFormat, item.session.time.created,
                            )
                        )
                        DetailRow(
                            stringResource(R.string.session_details_updated),
                            dateFormat.format(Date(item.session.time.updated))
                        )
                        // UI-B：DSH Agent 预设只读标签（name；unknown/undefined → —）
                        if (agentPresetSupported) {
                            val presetId = item.session.agentPreset
                            DetailRow(
                                stringResource(R.string.session_details_agent_preset),
                                presetId?.let { agentPresetNames[it] } ?: "—"
                            )
                        }
                        val summary = item.session.summary
                        if (summary != null) {
                            // #120（D2-32）：硬编码英文 → 15 语言资源
                            DetailRow(
                                stringResource(R.string.session_details_diff),
                                stringResource(R.string.session_details_diff_summary, summary.additions, summary.deletions, summary.files)
                            )
                        }
                    }
                }
                // #271：同步详情区——状态（未同步/同步中/已同步/失败·原因）+
                // lastSyncAt + 已入库提示 + 「同步全部历史」/「取消同步」按钮。
                // drain 静默后台运行，完成无提示（spec §2.5 四轮定稿）。
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                    Text(
                        text = stringResource(R.string.session_sync_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val state = syncState?.state
                    val statusColor = when (state) {
                        dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCING -> MaterialTheme.colorScheme.primary
                        dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCED -> DiffAdded
                        dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    DetailRow(
                        stringResource(R.string.session_details_status),
                        stringResource(
                            when (state) {
                                dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCING -> R.string.session_sync_state_syncing
                                dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCED -> R.string.session_sync_state_synced
                                dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_FAILED -> R.string.session_sync_state_failed
                                else -> R.string.session_sync_state_none
                            }
                        ),
                    )
                    Text(
                        text = run {
                            if (state == dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_FAILED && !syncState?.errorMessage.isNullOrBlank()) {
                                stringResource(R.string.session_sync_failed_reason, syncState?.errorMessage.orEmpty())
                            } else {
                                stringResource(
                                    if (state == dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCED) {
                                        R.string.session_sync_hint_synced
                                    } else {
                                        R.string.session_sync_hint_partial
                                    }
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                    val lastSyncAt = syncState?.lastSyncAt
                    if (state == dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCED && lastSyncAt != null) {
                        Text(
                            text = stringResource(R.string.session_sync_last_at, dateFormat.format(Date(lastSyncAt))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        )
                    }
                    if (state == dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCING) {
                        // 同步中 → 显示「取消同步」（可打断 drain，状态回未同步）
                        Button(
                            onClick = onCancelSync,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.session_sync_cancel))
                        }
                    } else {
                        // 已同步 → 禁用（drain 已全量，重复触发无意义）；其余状态可手动触发
                        Button(
                            onClick = onRequestSync,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state != dev.leonardo.ocbeacon.data.local.SessionSyncEntity.STATE_SYNCED,
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.session_sync_action))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                ) {
                    // 第一行：复制会话 ID + 重命名会话
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                    ) {
                        Button(
                            onClick = { onCopyId() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.menu_copy_session_id))
                        }
                        Button(
                            onClick = {
                                onDismiss()
                                onRename()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.session_rename))
                        }
                    }
                    // 第二行：分配 Tag
                    Button(
                        onClick = {
                            onDismiss()
                            onAssignCategory()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonTokens.filledColors(),
                        border = ButtonTokens.amoledBorder(),
                    ) {
                        Text(stringResource(R.string.assign_tag))
                    }
                    // #177：堆积队列非空时的手动「继续」入口（状态补偿的显式逃生口）
                    if (pendingCount > 0) {
                        Button(
                            onClick = {
                                onDismiss()
                                onContinueQueue()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.session_details_continue_queue, pendingCount))
                        }
                    }
                    // 第三行：删除（#276 能力位门控：DSH 无 session.delete——隐藏）
                    if (deleteSupported) {
                        Button(
                            onClick = {
                                onDismiss()
                                onDelete()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonTokens.dangerColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.session_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChipsRow(tags: List<Tag>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
    ) {
        tags.forEach { tag ->
            TagBadge(tag)
        }
    }
}
