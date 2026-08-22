package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** 边缘拉杆贴靠侧。 */
internal enum class FabEdge { START, END }

/** #192 v2 锚点值（Foundation 官方 AnchoredDraggable 引擎驱动）。 */
internal enum class FabSwipeAnchor { Visible, Hidden }

/** #192 隐藏滑出距离（dp）：FAB 向屏缘平移量（44dp 自身 + 16dp 边距）。 */
internal val FabExitDistance = 60.dp

/** #192 拉杆可拖出的最大跟手位移（dp）。 */
internal val FabTabPullMax = 48.dp

/**
 * v2 重写定案（2026-08-23，用户指令：先调研 M3 是否支持、不手搓）：
 * - M3 无「FAB 滑动隐藏 + 边缘拉杆恢复」成品组件——FAB 族 / SwipeToDismissBox
 *   （列表项 dismiss 语义）/ BottomAppBar hideOnScroll（滚动联动语义）均不覆盖；
 * - 但官方原语齐备，v1 手搓实现全部替换：
 *   1) 手势引擎 = Foundation [anchoredDraggable]（BottomSheet/SwipeToDismissBox 同款；
 *      默认 fling 已含过半 PositionalThreshold + 速度阈值 + settle 动画，零自写阈值）；
 *   2) 拉杆视觉 = M3 官方 [VerticalDragHandle]（DragHandleTokens：默认 4x48dp Outline
 *      全圆角胶囊；压按/拖拽 12x52dp OnSurface 变宽变色反馈）；
 * - 第三方 UI 库按 AGENTS.md 铁律排除。
 */

/**
 * #192 v2：FAB 滑动隐藏引擎容器（官方 anchoredDraggable 封装）。
 *
 * [dragSign] 隐藏方向物理符号（end 侧 +1 向右 / start 侧 -1 向左；RTL 由调用方换算）。
 * settle 到 Hidden → [onHidden]（上层切拉杆，本容器随即离树）。
 *
 * align 挂载点（v1 渲染 bug 教训）：align 是 ParentDataModifier，只对直接父 Box 生效
 * ——含 BottomStart/BottomEnd 的 Modifier 必须挂在本容器（ChatScreen Box 直接子级）上，
 * 内部内容用裸 Modifier。
 */
@Composable
private fun SwipeHideFabContainer(
    dragSign: Float,
    onHidden: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val exitPx = with(density) { FabExitDistance.toPx() }
    val state = remember { AnchoredDraggableState(FabSwipeAnchor.Visible) }
    LaunchedEffect(exitPx) {
        state.updateAnchors(
            DraggableAnchors {
                FabSwipeAnchor.Visible at 0f
                FabSwipeAnchor.Hidden at exitPx
            }
        )
    }
    // settle 检测：currentValue 仅在 settle 完成时翻转（引擎保证），进入 Hidden 即通知
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }
            .collect { if (it == FabSwipeAnchor.Hidden) onHidden() }
    }
    Box(
        Modifier
            .graphicsLayer {
                // 首帧守卫：updateAnchors 在 LaunchedEffect 派发，首帧 draw 时 offset
                // 仍为 NaN——requireOffset() 抛 ISE 崩溃（真机 05:08 FATAL 实证）。NaN 视为 0。
                val off = state.offset
                translationX = (if (off.isNaN()) 0f else off) * dragSign
            }
            .anchoredDraggable(
                state = state,
                reverseDirection = dragSign < 0, // start 侧：向左拖产生正 offset
                orientation = Orientation.Horizontal,
            )
    ) { content() }
}

/**
 * #192 v2：边缘拉杆（官方 VerticalDragHandle + anchoredDraggable 拖拽恢复）。
 *
 * D4 双通道：点按即恢复；拖拽跟手（[FabTabPullMax] 上限），过半（官方默认
 * PositionalThreshold = distance/2）松手自动恢复并回弹。D6：badge>0 叠官方 Badge。
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
    // 拖出方向物理符号：START 缘拉杆向右拖出（+1），END 缘向左（-1）；RTL 取反
    val pullSign = when (edge) {
        FabEdge.START -> if (rtl) -1f else 1f
        FabEdge.END -> if (rtl) 1f else -1f
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pullMaxPx = with(density) { FabTabPullMax.toPx() }
    val state = remember { AnchoredDraggableState(FabSwipeAnchor.Visible) }
    LaunchedEffect(pullMaxPx) {
        state.updateAnchors(
            DraggableAnchors {
                FabSwipeAnchor.Visible at 0f
                FabSwipeAnchor.Hidden at pullMaxPx
            }
        )
    }
    // 过半松手 → 恢复 FAB；拉杆不随 FAB 回归离树，需显式回弹原位
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }
            .collect {
                if (it == FabSwipeAnchor.Hidden) {
                    onRestore()
                    scope.launch { state.animateTo(FabSwipeAnchor.Visible) }
                }
            }
    }
    BadgedBox(
        badge = {
            if (badge != null && badge > 0) {
                Badge { Text(badge.coerceAtMost(99).toString()) }
            }
        },
        modifier = modifier
            .padding(bottom = 16.dp)
            .graphicsLayer {
                val off = state.offset
                translationX = (if (off.isNaN()) 0f else off) * pullSign
            }
            .anchoredDraggable(
                state = state,
                reverseDirection = pullSign < 0,
                orientation = Orientation.Horizontal,
            )
            .clickable { onRestore() }
            .semantics { this.contentDescription = contentDescription },
    ) {
        VerticalDragHandle()
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
 * #192 v2：[hidden]/[onHide]/[onRestore] 会话级滑动隐藏（官方 anchoredDraggable）。
 * D5 两段式：展开态右划仅收拢（复用既有 collapse 语义），收起态右划才隐藏。
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

    // align 挂本容器（直接子级才吃 ParentData）；D5 在 onHidden 前拦截展开态
    SwipeHideFabContainer(
        dragSign = +1f,
        onHidden = { if (expanded) expanded = false else onHide() },
    ) {
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
 * （FloatingActionButtonMenuItem 源码同款手法），双圆严格同径。
 * isAtBottom 的 .value 读取限制在本函数小作用域（B-F5 重组隔离沿袭）。
 *
 * #192 v2：[hidden]/[onHide]/[onRestore]（官方 anchoredDraggable 引擎）。
 * D3：手动隐藏优先——hidden 期间不因滚离底部自动出现（拉杆恒在）。
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
        SwipeHideFabContainer(dragSign = -1f, onHidden = onHide) {
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
