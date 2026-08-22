package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingActionButtonMenuScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/**
 * 主对话右下角 FAB Menu（第十八轮定案：M3 原版样式，零定制）。
 *
 * 官方 FloatingActionButtonMenu + ToggleFloatingActionButton 全默认参数：
 * 56dp 按钮（点击时圆角 16→28dp morph + primaryContainer→primary 变色）+
 * 56dp primaryContainer 药丸菜单项（titleMedium/24dp 图标）。
 * 唯一保留的定制：角标计数（Badge，功能性）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFabMenu(
    stackedCount: Int,
    todoPendingCount: Int,
    agentRunningCount: Int,
    shellRunningCount: Int,
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

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                // 描边（第二十轮，用户要求）：角半径冻结 16dp——形状恒定描边才贴边
                modifier = Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(16.dp),
                ),
                // 尺寸（第二十轮：用户「稍微大一些」）44→展开 48dp；
                // 色彩/尺寸 morph 保留，角 morph 冻结（与描边形状匹配）
                containerSize = ToggleFloatingActionButtonDefaults.containerSize(44.dp, 48.dp),
                containerCornerRadius =
                    ToggleFloatingActionButtonDefaults.containerCornerRadius(16.dp, 16.dp),
                // Secondary 变体（第十九轮，用户选 B）：官方规格三变体之一——
                // secondaryContainer→secondary，与用户气泡（primaryContainer 系）区分
                containerColor = ToggleFloatingActionButtonDefaults.containerColor(
                    initialColor = MaterialTheme.colorScheme.secondaryContainer,
                    finalColor = MaterialTheme.colorScheme.secondary,
                ),
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

/** FAB 菜单入口项（M3 全默认：56dp primaryContainer 药丸/titleMedium/24dp 图标；角标挂 icon）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatingActionButtonMenuScope.FabMenuEntry(
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    FloatingActionButtonMenuItem(
        onClick = onClick,
        // 高度 44dp（官方 56dp）+ stadium 描边（第二十轮，与按钮描边同族）
        modifier = Modifier
            .height(44.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
        // Secondary 变体（第十九轮）：药丸 secondaryContainer 系，与用户气泡区分
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        text = { Text(label) },
        icon = {
            if (count > 0) {
                BadgedBox(
                    badge = { Badge { Text(count.coerceAtMost(99).toString()) } }
                ) {
                    Icon(icon, contentDescription = null)
                }
            } else {
                Icon(icon, contentDescription = null)
            }
        },
    )
}

/**
 * 滚动到底部 FAB（M3 原版样式）：标准 FloatingActionButton（16dp 圆角），
 * Secondary 变体（第十九轮，与菜单 FAB 同族）；底部居中；16dp 底距对齐菜单
 * FAB 基线；在底时隐藏。
 * isAtBottom 的 .value 读取限制在本函数小作用域（B-F5 重组隔离沿袭）。
 */
@Composable
internal fun ChatScrollBottomFab(
    isAtBottomState: State<Boolean>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isAtBottomState.value) return // 在底部时不显示
    FloatingActionButton(
        onClick = onClick,
        // 16dp 底距 = 菜单内部按钮下距（FabMenuButtonPaddingBottom），双 FAB 同基线
        // 44dp 与菜单 FAB 同档（第二十轮加大）+ 描边（角 16dp=FAB 默认角）
        modifier = modifier
            .padding(bottom = 16.dp)
            .size(44.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.chat_scroll_bottom),
        )
    }
}
