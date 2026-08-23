package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlin.math.roundToInt

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/**
 * #192 v6（2026-08-23 定案，用户指令「不做隐藏了，按钮贴边上下滑动，做简单点」）：
 * FAB 保持常驻显示，支持沿所在屏缘**垂直拖动**——
 * - 拖动跟随手指（offset 布局位移，命中区同步移动），松手即停在原处；
 * - 上限：按钮顶缘不越过屏高 − 160dp（避开顶栏）；下限 0（回到底部原位）；
 * - 位移 rememberSaveable（会话内保持，返回栈弹出/进程重启复位）；
 * - 点击语义不变（detectVerticalDragGestures 过 touch slop 才消费，tap 照常）；
 * - 旧「滑动隐藏/Peek/边缘拉杆」全部移除（v1–v5 代码与状态类一并清理）。
 */
private fun Modifier.fabEdgeVerticalSlide(): Modifier = composed {
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val maxUpPx = with(density) {
        (LocalConfiguration.current.screenHeightDp.dp - 160.dp).toPx()
    }
    this
        .offset { IntOffset(0, offsetY.roundToInt()) }
        .pointerInput(Unit) {
            detectVerticalDragGestures { change, dragAmount ->
                change.consume()
                offsetY = (offsetY + dragAmount).coerceIn(-maxUpPx, 0f)
            }
        }
}

/**
 * 主对话右下角 FAB Menu（第十八轮定案：M3 原版样式，零定制）。
 *
 * 官方 FloatingActionButtonMenu + ToggleFloatingActionButton 全默认参数：
 * 56dp 按钮（点击时圆角 16→28dp morph + primaryContainer→primary 变色）+
 * 56dp primaryContainer 药丸菜单项（titleMedium/24dp 图标）。
 * 唯一保留的定制：角标计数（Badge，功能性）。
 *
 * 尺寸（2026-08-23 用户「按钮再大一些，item 不变」）：48→展开 52dp；item 保持 44dp。
 * #192 v6：贴边上下滑动（fabEdgeVerticalSlide，位移作用于整个菜单）。
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
        modifier = modifier.fabEdgeVerticalSlide(),
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
                // 尺寸（2026-08-23 用户「稍微再大一些」）48→展开 52dp；
                // 色彩/尺寸 morph 保留，角 morph 冻结（与描边形状匹配）
                containerSize = ToggleFloatingActionButtonDefaults.containerSize(48.dp, 52.dp),
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
                    // tint 显式统一（第二十一轮）：Toggle 不吃 contentColor 参数，
                    // 不显式给会落 LocalContentColor（与 ⬇ FAB 图标色不一致）
                    Icon(
                        if (checkedProgress >= 0.5f) Icons.Default.Close else Icons.Default.Inbox,
                        contentDescription = desc,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
        // 高度 44dp（官方 56dp，2026-08-23 用户指示 item 保持现状）+ stadium 描边（第二十轮）
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
 * 滚动到底部 FAB：底部左侧（与右下菜单 FAB 镜像，start 16dp=菜单内部横向 padding），
 * 与菜单 FAB 完全同规格：48dp（2026-08-23 用户「稍微再大一些」44→48dp）/圆角 16dp/
 * secondaryContainer/1dp outline 描边/24dp 图标 onSecondaryContainer tint。
 *
 * 一致性关键（第二十一轮实测修复）：普通 FloatingActionButton 内部强制
 * LocalMinimumInteractiveComponentSize(48dp) 最小触达，44dp 会被顶到 48dp——
 * 与 Toggle FAB（不吃该机制）差 4dp。此处 provision 0dp 关闭强制
 * （FloatingActionButtonMenuItem 源码同款手法），双圆严格同径。
 * isAtBottom 的 .value 读取限制在本函数小作用域（B-F5 重组隔离沿袭）。
 *
 * #192 v6：贴边上下滑动（fabEdgeVerticalSlide）。
 */
@Composable
internal fun ChatScrollBottomFab(
    isAtBottomState: State<Boolean>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isAtBottomState.value) return // 在底部时不显示
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FloatingActionButton(
            onClick = onClick,
            // 16dp 底距 = 菜单内部按钮下距（FabMenuButtonPaddingBottom），双 FAB 同基线
            modifier = modifier
                .fabEdgeVerticalSlide()
                .padding(start = 16.dp, bottom = 16.dp)
                .size(48.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_bottom),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
