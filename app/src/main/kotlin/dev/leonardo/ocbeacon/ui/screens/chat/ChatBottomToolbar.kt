package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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

/** 工具栏入口 id（第十轮定案：五入口，四个独立 sheet 无 tab）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/**
 * 主对话贴底工具栏（2026-08-22 第十轮：任务面板拆解并入——用户定案）。
 *
 * 恒显示（有任务/堆积价值常在）：[⬇] [堆积 N] [TODO n] [agent n] [shell n]
 * - ⬇ = 原滚到底 FAB 并入（在底部时该钮隐藏）
 * - 四入口 = 四个独立 ModalBottomSheet（无 tab 隔离；GitHub 式行内计数 =
 *   运行中/待处理数；0 不显数字但可点击看历史——用户 Q4 定案）
 * - 键盘弹起时被键盘自然盖住（贴消息区底，不占输入区）
 */
@Composable
internal fun ChatBottomToolbar(
    showScrollBottom: Boolean,
    stackedCount: Int,
    todoPendingCount: Int,
    agentRunningCount: Int,
    shellRunningCount: Int,
    onScrollToBottom: () -> Unit,
    onOpenEntry: (ChatToolbarEntry) -> Unit,
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
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showScrollBottom) {
                IconButton(onClick = onScrollToBottom, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_scroll_bottom),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ToolbarEntry(
                label = stringResource(R.string.pending_tab_stacked_plain),
                count = stackedCount,
                onClick = { onOpenEntry(ChatToolbarEntry.STACKED) },
            )
            ToolbarEntry(
                label = stringResource(R.string.pending_tab_todo_plain),
                count = todoPendingCount,
                onClick = { onOpenEntry(ChatToolbarEntry.TODO) },
            )
            ToolbarEntry(
                label = stringResource(R.string.toolbar_agent),
                count = agentRunningCount,
                onClick = { onOpenEntry(ChatToolbarEntry.AGENT) },
            )
            ToolbarEntry(
                label = stringResource(R.string.toolbar_shell),
                count = shellRunningCount,
                onClick = { onOpenEntry(ChatToolbarEntry.SHELL) },
            )
        }
    }
}

/** 入口（GitHub 式行内计数；count=0 只显文字——可点看历史）。 */
@Composable
private fun ToolbarEntry(
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 0.dp
        ),
        modifier = Modifier.height(32.dp),
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
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}