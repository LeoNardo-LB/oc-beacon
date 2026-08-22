package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlinx.coroutines.launch

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/** 边缘拉杆贴靠侧（start/end 随布局方向，物理方向在内部换算）。 */
internal enum class FabEdge { START, END }

/**
 * #192 手势判定阈值（dp）：隐藏方向水平累计位移超过即触发。
 * 真机 E2E 修正（2026-08-23）：原 40dp > 左 FAB 可拖行程（中心距屏缘仅
 * ~38dp=16dp 边距+22dp 半径）→ 左 FAB 物理不可隐藏。降到 24dp（可用行程
 * 38dp 的 ~63%，误触与可达性平衡；右 FAB 行程充裕不受影响）。
 */
internal val FabSwipeThreshold = 24.dp

/** #192 隐藏滑出距离（dp）：FAB 向屏缘平移量（44dp 自身 + 16dp 边距）。 */
internal val FabExitDistance = 60.dp

/** #192 拉杆可拖出的最大跟手位移（dp），松手过半即恢复。 */
internal val FabTabPullMax = 48.dp

/**
 * #192：FAB 隐藏手势容器（composable 层，状态在重组间稳定）。
 *
 * 语义（spec §3.2）：水平拖动向屏缘跟手平移 + 渐隐；松手超过 [FabSwipeThreshold]
 * → 滑出动画完成回调 [onHide]（上层切拉杆）；未过阈值 → 弹回。
 *
 * [dragSign]：隐藏方向物理符号——start 侧 FAB 在 LTR 下向左（-1），end 侧向右（+1）；
 * RTL 自动取反（依赖 [LocalLayoutDirection]）。手势修饰符挂 [content] 外层 Box。
 */
@Composable
private fun SwipeToHideBox(
    dragSign: Float,
    onHide: () -> Unit,
    content: @Composable () -> Unit,
) {
    val layoutDir = LocalLayoutDirection.current
    val rtl = layoutDir == LayoutDirection.Rtl
    val sign = if (rtl) -dragSign else dragSign
    val exit = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val exitPx = with(density) { FabExitDistance.toPx() }
    val thresholdPx = with(density) { FabSwipeThreshold.toPx() }
    Box(
        Modifier
            .graphicsLayer {
                translationX = exit.value * exitPx * sign
                alpha = 1f - exit.value
            }
            .pointerInput(sign) {
                detectDragGestures(
                    onDrag = { _, dragAmount ->
                        val next = (exit.value + dragAmount.x * sign).coerceIn(0f, 1f)
                        scope.launch { exit.snapTo(next) }
                    },
                    onDragEnd = {
                        if (exit.value * exitPx >= thresholdPx) {
                            scope.launch {
                                exit.animateTo(1f, tween(durationMillis = 160))
                                onHide()
                                exit.snapTo(0f) // 复位：重组切到拉杆后本容器即离树
                            }
                        } else {
                            scope.launch { exit.animateTo(0f, spring()) }
                        }
                    },
                )
            }
    ) { content() }
}

/**
 * #192：边缘拉杆（D7 形态 + D4 双通道恢复 + D6 角标）。
 *
 * 贴 [FabEdge] 侧屏缘、与原 FAB 同底边（bottom 16dp）；半圆凸出 ~10dp、高 28dp、
 * 半透明 secondaryContainer。点按即恢复；按住向屏内拖（跟手位移，[FabTabPullMax] 内），
 * 松手过半自动恢复 + 回弹（Animatable spring）。角标实时（badge 参数每次重组刷新）。
 */
@Composable
internal fun FabEdgeTab(
    edge: FabEdge,
    badge: Int?,
    contentDescription: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDir = LocalLayoutDirection.current
    val rtl = layoutDir == LayoutDirection.Rtl
    // 物理向内方向：START 侧拉杆向右拖（+1），END 侧向左（-1）；RTL 取反
    val inwardSign = if (edge == FabEdge.START) (if (rtl) -1f else 1f) else (if (rtl) 1f else -1f)
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pullMaxPx = with(density) { FabTabPullMax.toPx() }
    val pullThresholdPx = pullMaxPx / 2
    // 半圆：贴屏缘侧全圆角（50% 百分比重载）。START 侧圆角在右侧、END 侧在左侧——
    // 由布局方向换算：拉杆凸出于屏缘，圆角面朝屏幕内侧
    val shape = when (edge) {
        FabEdge.START -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
        FabEdge.END -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    }
    val arrow: ImageVector = when (edge) {
        FabEdge.START -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        FabEdge.END -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    }
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier
            .padding(bottom = 16.dp)
            .graphicsLayer {
                val p = pull.value.coerceIn(0f, 1f)
                translationX = p * pullMaxPx * inwardSign
            }
            .size(width = 20.dp, height = 28.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .semantics { this.contentDescription = contentDescription }
            .clickable { onRestore() }
            .pointerInput(inwardSign) {
                detectDragGestures(
                    onDrag = { _, dragAmount ->
                        val next = (pull.value + dragAmount.x * inwardSign / pullMaxPx).coerceIn(0f, 1f)
                        scope.launch { pull.snapTo(next) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (pull.value * pullMaxPx >= pullThresholdPx) {
                                pull.animateTo(0f, tween(80))
                                onRestore()
                            } else {
                                pull.animateTo(0f, spring())
                            }
                        }
                    },
                )
            }
    ) {
        if (badge != null && badge > 0) {
            BadgedBox(
                badge = { Badge { Text(badge.coerceAtMost(99).toString()) } },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(arrow, contentDescription = null, tint = onColor, modifier = Modifier.size(16.dp))
            }
        } else {
            Icon(arrow, contentDescription = null, tint = onColor, modifier = Modifier.align(Alignment.Center))
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
 * #192：[hidden]/[onHide]/[onRestore] 支持会话级滑动隐藏（spec
 * 2026-08-23-fab-swipe-hide-design）——收起态右划过阈值隐藏（D5：展开态右划
 * 仅收拢，复用既有 collapse 语义，不隐藏）。
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
    hidden: Boolean = false,
    onHide: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    if (hidden) {
        FabEdgeTab(
            edge = FabEdge.END,
            badge = stackedCount + todoPendingCount + agentRunningCount + shellRunningCount,
            contentDescription = stringResource(R.string.chat_fab_edge_tab_menu),
            onRestore = onRestore,
            modifier = modifier,
        )
        return
    }

    // 外点收起层（仅展开时存在；无视觉、整屏拦截，画在菜单之下）
    if (expanded) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { expanded = false } }
        )
    }

    val totalBadge = stackedCount + todoPendingCount + agentRunningCount + shellRunningCount

    // D5 两段式：展开态右划仅收拢（与 back/外点同语义），收起态右划才隐藏
    SwipeToHideBox(dragSign = +1f, onHide = { if (expanded) expanded = false else onHide() }) {
        FloatingActionButtonMenu(
            expanded = expanded,
            modifier = Modifier,
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
 * 滚动到底部 FAB：底部左侧（与右下菜单 FAB 镜像，start 16dp=菜单内部横向 padding），
 * 与菜单 FAB 完全同规格：44dp/圆角 16dp/secondaryContainer/1dp outline 描边/
 * 24dp 图标 onSecondaryContainer tint。
 *
 * 一致性关键（第二十一轮实测修复）：普通 FloatingActionButton 内部强制
 * LocalMinimumInteractiveComponentSize(48dp) 最小触达，44dp 会被顶到 48dp——
 * 与 Toggle FAB（不吃该机制，44dp 原样）差 4dp。此处 provision 0dp 关闭强制
 *（FloatingActionButtonMenuItem 源码同款手法），双圆严格同径。
 * isAtBottom 的 .value 读取限制在本函数小作用域（B-F5 重组隔离沿袭）。
 *
 * #192：[hidden]/[onHide]/[onRestore] 支持会话级滑动隐藏；[fabVisible] 由上层
 * 用 ChatFabVisibilityState.bottomFabSlot 计算（D3：手动隐藏优先，隐藏期不因
 * 滚离底部自动出现）。
 */
@Composable
internal fun ChatScrollBottomFab(
    isAtBottomState: State<Boolean>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    onHide: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    if (hidden) {
        FabEdgeTab(
            edge = FabEdge.START,
            badge = null,
            contentDescription = stringResource(R.string.chat_fab_edge_tab_scroll),
            onRestore = onRestore,
            modifier = modifier,
        )
        return
    }
    if (isAtBottomState.value) return // 在底部时不显示
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SwipeToHideBox(dragSign = -1f, onHide = onHide) {
            FloatingActionButton(
                onClick = onClick,
                // 16dp 底距 = 菜单内部按钮下距（FabMenuButtonPaddingBottom），双 FAB 同基线
                modifier = Modifier
                    .padding(start = 16.dp, bottom = 16.dp)
                    .size(44.dp)
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
}
