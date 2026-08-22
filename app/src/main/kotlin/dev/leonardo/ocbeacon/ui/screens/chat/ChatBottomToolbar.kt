package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R

/** 工具栏入口 id（第十轮：四入口独立 sheet）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/**
 * 主对话贴底工具栏（2026-08-22 第十一轮：M3 原生 BottomAppBar——用户定案）。
 *
 * 官方组件 + 官方角标模式（BadgedBox→IconButton，M3 文档标准写法）：
 * actions = [⬇(在底时藏) | 📥堆积 | ☑TODO | 🌳智能体 | >_Shell]
 * 图标沿用 TaskSheet 原 tab 图标（AccountTree/Terminal），语义连贯。
 * 计数 = 运行中/待处理数（0 不显角标；无数据仍可点看历史）。
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
    BottomAppBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        actions = {
            if (showScrollBottom) {
                ToolbarAction(
                    icon = { tint ->
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_scroll_bottom), modifier = Modifier.size(24.dp), tint = tint)
                    },
                    count = 0,
                    contentDescription = stringResource(R.string.chat_scroll_bottom),
                    onClick = onScrollToBottom,
                )
            }
            ToolbarAction(
                icon = { tint ->
                    Icon(Icons.Default.Inbox, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                },
                count = stackedCount,
                contentDescription = stringResource(R.string.pending_tab_stacked_plain),
                onClick = { onOpenEntry(ChatToolbarEntry.STACKED) },
            )
            ToolbarAction(
                icon = { tint ->
                    Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                },
                count = todoPendingCount,
                contentDescription = stringResource(R.string.pending_tab_todo_plain),
                onClick = { onOpenEntry(ChatToolbarEntry.TODO) },
            )
            ToolbarAction(
                icon = { tint ->
                    Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                },
                count = agentRunningCount,
                contentDescription = stringResource(R.string.toolbar_agent),
                onClick = { onOpenEntry(ChatToolbarEntry.AGENT) },
            )
            ToolbarAction(
                icon = { tint ->
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                },
                count = shellRunningCount,
                contentDescription = stringResource(R.string.toolbar_shell),
                onClick = { onOpenEntry(ChatToolbarEntry.SHELL) },
            )
            // 右侧弹性空间（BottomAppBar actions 左对齐惯例留白）
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        },
    )
}

/** 原生工具栏动作：BadgedBox + IconButton（M3 官方角标模式）。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolbarAction(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    count: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    if (count > 0) {
        BadgedBox(
            badge = {
                Badge { Text(text = count.coerceAtMost(99).toString()) }
            },
        ) {
            IconButton(onClick = onClick) { icon(tint) }
        }
    } else {
        IconButton(onClick = onClick) { icon(tint) }
    }
}