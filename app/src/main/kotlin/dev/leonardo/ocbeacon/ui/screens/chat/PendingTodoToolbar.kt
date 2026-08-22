package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.PendingMessage
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 堆积/TODO 贴底工具栏（2026-08-22 第九轮：入口与容器解耦）。
 *
 * 用户定案：工具栏只是「拉起抽屉的入口」——恒定一行贴底（输入栏上方）。
 * 点段入口拉起 PendingTodoDrawer（覆盖式抽屉本体零改动：三档/拖拽/圆角）。
 * 抽屉展开时本工具栏不渲染（抽屉独占）；双空时本工具栏也不渲染（原滚到底
 * FAB 恢复独立显示——由 ChatScreen 按同一条件切换）。
 *
 * 布局：[⬇滚到底(不在底时)] [堆积 N] [TODO n] —— [▶继续] [🗑清空]
 * 段入口 = TextButton 胶囊（GitHub 式行内计数）；计数 0 置灰。
 */
@Composable
internal fun PendingTodoToolbar(
    queue: List<PendingMessage>,
    todos: List<SseEvent.TodoUpdated.Todo>,
    showTodoEntry: Boolean,
    isSessionIdle: Boolean,
    isDraining: Boolean,
    onOpenDrawer: (segment: Int) -> Unit,
    onScrollToBottom: () -> Unit,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
        ) {
            // 滚到底（原 FAB 职责并入；在底部时不显示——调用方按需传入）
            IconButton(onClick = onScrollToBottom, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_bottom),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 段入口：GitHub 式行内计数（第八轮样式延续）
            ToolbarEntry(
                label = stringResource(R.string.pending_tab_stacked_plain),
                count = queue.size,
                enabled = queue.isNotEmpty(),
                onClick = { onOpenDrawer(0) },
            )
            if (showTodoEntry) {
                val pending = todos.count { it.status == "pending" || it.status == "in_progress" }
                ToolbarEntry(
                    label = stringResource(R.string.pending_tab_todo_plain),
                    count = pending,
                    enabled = showTodoEntry && todos.isNotEmpty(),
                    onClick = { onOpenDrawer(1) },
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            // 右端操作（堆积队列非空时）
            if (queue.isNotEmpty()) {
                if (isSessionIdle && !isDraining) {
                    IconButton(onClick = onContinue, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.pending_continue),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.pending_clear),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    )
                }
            }
        }
    }
}

/** 段入口（GitHub 式行内计数胶囊；enabled=false 置灰）。 */
@Composable
private fun ToolbarEntry(
    label: String,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 0.dp
        ),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count > 0) {
                Text(
                    text = count.coerceAtMost(99).toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}