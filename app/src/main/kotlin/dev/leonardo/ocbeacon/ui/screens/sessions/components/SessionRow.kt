package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DetailRow
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.DiffAdded
import dev.leonardo.ocbeacon.ui.theme.DiffRemoved
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
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
    showDirectory: Boolean = false,
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
            .padding(start = if (showDirectory) 12.dp else 28.dp, end = 8.dp)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        val (statusIcon, statusIconColor) = when (item.status) {
            is SessionStatus.Busy -> Icons.Filled.ChatBubble to MaterialTheme.colorScheme.tertiary
            is SessionStatus.Retry -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            else -> Icons.Outlined.ChatBubbleOutline to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
        }
        Icon(
            imageVector = statusIcon,
            contentDescription = stringResource(R.string.a11y_icon_toggle_session),
            modifier = Modifier.size(20.dp),
            tint = statusIconColor,
        )
        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.session.title ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyMedium,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )

            // Directory subtitle (shown in Recent mode)
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateFormat.format(Date(item.session.time.updated)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )

                // Status label
                when (item.status) {
                    is SessionStatus.Busy -> {
                        Text(
                            text = stringResource(R.string.sessions_working),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
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

                // Draft indicator
                if (item.hasDraft) {
                    Icon(
                        imageVector = Icons.Outlined.EditNote,
                        contentDescription = "草稿",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    )
                }

                // Category tag
                item.category?.let { category ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SessionCategoryStyle.color(category.color).copy(alpha = AlphaTokens.SELECTED))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = SessionCategoryStyle.icon(category.icon),
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = SessionCategoryStyle.color(category.color),
                        )
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = SessionCategoryStyle.color(category.color),
                            maxLines = 1,
                        )
                    }
                }

                // Diff summary
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
            }
        }
    }

    // Details dialog with actions
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
            isFavorite = isFavorite,
            onToggleFavorite = {
                showDetailsDialog = false
                onToggleFavorite()
            },
            isAmoled = isAmoled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: () -> Unit,
    onAssignCategory: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
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
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.session_session_details),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            dateFormat.format(Date(item.session.time.created))
                        )
                        DetailRow(
                            stringResource(R.string.session_details_updated),
                            dateFormat.format(Date(item.session.time.updated))
                        )
                        val summary = item.session.summary
                        if (summary != null) {
                            DetailRow(
                                "Diff",
                                "+${summary.additions} -${summary.deletions} (${summary.files} files)"
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.menu_copy_session_id), DialogButtonRole.Primary, onCopyId),
                        Triple("分配分类", DialogButtonRole.Primary) { onDismiss(); onAssignCategory() },
                        Triple(if (isFavorite) "取消收藏" else "收藏", DialogButtonRole.Primary) { onDismiss(); onToggleFavorite() },
                        Triple(stringResource(R.string.session_rename), DialogButtonRole.Primary) { onDismiss(); onRename() },
                        Triple(stringResource(R.string.session_delete), DialogButtonRole.Danger) { onDismiss(); onDelete() },
                    )
                )
            }
        }
    }
}
