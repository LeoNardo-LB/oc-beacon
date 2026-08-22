package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingActionButtonMenuScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/**
 * 主对话右下角 FAB Menu（2026-08-22 第十五轮定案：M3 官方 FloatingActionButtonMenu——
 * m3.material.io FAB menu 形态）。
 *
 * 收起 = 单个小 FAB（40dp，与退役的滚底 SmallFAB 同尺寸规格；角标=四入口总数）；
 * 展开 = 官方交错动画菜单，自上而下 [⬇(在底时置灰) | 📥堆积 | ☑TODO | 🌳智能体 | >_Shell]，
 * 各项角标=运行中/待处理数（0 无角标仍可点看历史）。
 * 展开 dismissal：外点任意处 / 返回键；点入口先收菜单再开对应 sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFabMenu(
    canScrollToBottom: Boolean,
    stackedCount: Int,
    todoPendingCount: Int,
    agentRunningCount: Int,
    shellRunningCount: Int,
    onScrollToBottom: () -> Unit,
    onOpenEntry: (ChatToolbarEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    // 外点收起层（仅展开时存在；无视觉、整屏拦截，画在菜单之下）
    if (expanded) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { expanded = false } }
        )
    }

    val totalBadge = stackedCount + todoPendingCount + agentRunningCount + shellRunningCount
    val fabContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val fabContent = MaterialTheme.colorScheme.onSurface

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                containerColor = { fabContainer },
                // 大小适配本应用规格：40dp 小 FAB + 12dp 圆角（官方默认 56dp/16dp）
                containerSize = ToggleFloatingActionButtonDefaults.containerSize(40.dp, 40.dp),
                containerCornerRadius =
                    ToggleFloatingActionButtonDefaults.containerCornerRadius(12.dp, 12.dp),
            ) {
                val desc = if (checkedProgress >= 0.5f) {
                    stringResource(R.string.chat_fab_menu_close)
                } else {
                    stringResource(R.string.chat_fab_menu_open)
                }
                val fabIcon: @Composable () -> Unit = {
                    Icon(
                        if (checkedProgress >= 0.5f) Icons.Default.Close else Icons.Default.Inbox,
                        contentDescription = desc,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (checkedProgress < 0.5f && totalBadge > 0) {
                    BadgedBox(
                        badge = { Badge { Text(totalBadge.coerceAtMost(99).toString()) } }
                    ) { fabIcon() }
                } else {
                    fabIcon()
                }
            }
        },
    ) {
        // ⬇ 并入菜单（第十五轮定案）：在底时置灰不可点
        FloatingActionButtonMenuItem(
            onClick = {
                if (canScrollToBottom) {
                    expanded = false
                    onScrollToBottom()
                }
            },
            text = { Text(stringResource(R.string.chat_scroll_bottom)) },
            icon = {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            containerColor = if (canScrollToBottom) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (canScrollToBottom) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
        FabMenuEntry(
            icon = Icons.Default.Inbox,
            label = stringResource(R.string.pending_tab_stacked_plain),
            count = stackedCount,
            onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.STACKED) },
        )
        FabMenuEntry(
            icon = Icons.Default.Checklist,
            label = stringResource(R.string.pending_tab_todo_plain),
            count = todoPendingCount,
            onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.TODO) },
        )
        FabMenuEntry(
            icon = Icons.Default.AccountTree,
            label = stringResource(R.string.toolbar_agent),
            count = agentRunningCount,
            onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.AGENT) },
        )
        FabMenuEntry(
            icon = Icons.Default.Terminal,
            label = stringResource(R.string.toolbar_shell),
            count = shellRunningCount,
            onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.SHELL) },
        )
    }
}

/** FAB 菜单通用入口项（icon+label 官方排版，角标挂在 icon 上；count=0 无角标）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatingActionButtonMenuScope.FabMenuEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    FloatingActionButtonMenuItem(
        onClick = onClick,
        text = { Text(label) },
        icon = {
            if (count > 0) {
                BadgedBox(
                    badge = { Badge { Text(count.coerceAtMost(99).toString()) } }
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        },
    )
}
