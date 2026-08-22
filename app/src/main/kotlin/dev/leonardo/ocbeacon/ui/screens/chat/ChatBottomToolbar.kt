package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalFloatingToolbar
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
 * 主对话悬浮工具栏（2026-08-22 第十二轮：M3 官方 HorizontalFloatingToolbar——
 * m3.material.io/components/toolbars 用户指定样式）。
 *
 * 悬浮胶囊容器（自带阴影/圆角/间距令牌）+ 官方角标模式（BadgedBox→IconButton）：
 * [⬇(在底时藏) | 📥堆积 | ☑TODO | 🌳智能体 | >_Shell]
 * 计数=运行中/待处理数（0 无角标仍可点看历史）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    HorizontalFloatingToolbar(
        expanded = false,
        modifier = modifier,
    ) {
            if (showScrollBottom) {
                ToolbarAction(
                    icon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_scroll_bottom), modifier = Modifier.size(24.dp)) },
                    count = 0,
                    onClick = onScrollToBottom,
                )
                Spacer(Modifier.width(8.dp))
            }
            ToolbarAction(
                icon = { Icon(Icons.Default.Inbox, contentDescription = stringResource(R.string.pending_tab_stacked_plain), modifier = Modifier.size(24.dp)) },
                count = stackedCount,
                onClick = { onOpenEntry(ChatToolbarEntry.STACKED) },
            )
            ToolbarAction(
                icon = { Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.pending_tab_todo_plain), modifier = Modifier.size(24.dp)) },
                count = todoPendingCount,
                onClick = { onOpenEntry(ChatToolbarEntry.TODO) },
            )
            ToolbarAction(
                icon = { Icon(Icons.Default.AccountTree, contentDescription = stringResource(R.string.toolbar_agent), modifier = Modifier.size(24.dp)) },
                count = agentRunningCount,
                onClick = { onOpenEntry(ChatToolbarEntry.AGENT) },
            )
            ToolbarAction(
                icon = { Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.toolbar_shell), modifier = Modifier.size(24.dp)) },
                count = shellRunningCount,
                onClick = { onOpenEntry(ChatToolbarEntry.SHELL) },
            )
    }
}

/** 官方角标模式动作（BadgedBox→IconButton；count=0 无角标）。 */
@Composable
private fun ToolbarAction(
    icon: @Composable () -> Unit,
    count: Int,
    onClick: () -> Unit,
) {
    if (count > 0) {
        BadgedBox(
            badge = { Badge { Text(text = count.coerceAtMost(99).toString()) } },
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { icon() }
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { icon() }
    }
}