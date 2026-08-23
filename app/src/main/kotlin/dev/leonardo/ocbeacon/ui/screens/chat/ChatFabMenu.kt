package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlin.math.abs
import kotlinx.coroutines.launch

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/** 边缘拉杆贴靠侧。 */
internal enum class FabEdge { START, END }

/** #192 锚点值（Foundation 官方 AnchoredDraggable 引擎驱动）。 */
internal enum class FabSwipeAnchor { Visible, Hidden }

/**
 * v4 重写定案（2026-08-23，用户观感反馈「动画衔接不自然 + 拉杆应贴屏边」）：
 * - **单一连续动画**：跟手平移 → 松手 spring 继续同方向平移到完全出屏（锚点=实测
 *   容器宽度，官方 AnchoredDraggableLayoutDependentAnchorsSample 同款
 *   onSizeChanged 动态锚点）+ 按位移比例渐隐——一个动画系统从头管到尾，无
 *   「平移→snap→缩放」的属性切换（v3 衔接断裂根因）；
 * - **贴边拉杆**：自绘 5dp 宽半透明胶囊贴 x=0/屏宽（v3 用官方 VerticalDragHandle，
 *   其内部 48dp 最小触达宽使胶囊悬在触达区中央，视觉像「留在 FAB 原位」）；
 *   命中区独立（26x60dp 边缘对齐），点按/拖拽恢复；
 * - 官方 animateFloatingActionButton（v3）移除——它语义是滚动联动的 scale 显隐，
 *   与手势驱动的连续平移不匹配；
 * - 引擎/spring/threshold 仍全官方：anchoredDraggable + flingBehavior
 *   （positionalThreshold 40% 避 MIUI 返回手势区）。
 */

/**
 * #192 v4：FAB 滑动隐藏容器（单一连续平移动画）。
 *
 * [dragSign] 隐藏方向物理符号（end 侧 +1 向右 / start 侧 -1 向左；RTL 由调用方换算）。
 * [dragEnabled] false 时手势禁用（D5：菜单展开期右划走 scrim 收起，不隐藏不位移）。
 *
 * 动画链：入场（appear 0→1：从屏缘滑入 + 渐显）→ 拖拽（offset 1:1 跟手 + 按比例渐隐）
 * → 松手过阈值（spring 平移至 ±width 完全出屏，alpha 到 0）→ settle → [onHidden]。
 *
 * align 挂载点（教训保持）：align 是 ParentDataModifier 只对直接父 Box 生效——
 * [modifier]（含 BottomStart/BottomEnd）挂本容器（ChatScreen Box 直接子级）。
 */
@Composable
private fun SwipeHideFabContainer(
    modifier: Modifier = Modifier,
    dragSign: Float,
    onHidden: () -> Unit,
    dragEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = remember { AnchoredDraggableState(FabSwipeAnchor.Visible) }
    var widthPx by remember { mutableStateOf(Float.NaN) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(240)) }
    // 官方模式：锚点依赖尺寸 → onSizeChanged 里 updateAnchors（同帧就绪）
    LaunchedEffect(Unit) {
        snapshotFlow { widthPx }.collect { w ->
            if (!w.isNaN()) {
                state.updateAnchors(
                    DraggableAnchors {
                        // 官方符号约定（SwipeToDismissBox）：方向由锚点符号表达
                        FabSwipeAnchor.Visible at 0f
                        FabSwipeAnchor.Hidden at w * dragSign
                    }
                )
            }
        }
    }
    // settle 完成（引擎保证 currentValue 只在 settle 后翻转）→ 通知上层切拉杆
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }
            .collect { if (it == FabSwipeAnchor.Hidden) onHidden() }
    }
    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .graphicsLayer {
                // 首帧守卫：NaN（updateAnchors 未派发）视为 0（真机 FATAL 实证过）
                val off = state.offset
                val drag = if (off.isNaN()) 0f else off
                // 入场：从屏缘方向滑入（appear 0→1）
                val enter = (1f - appear.value) * 96f * dragSign
                translationX = drag + enter
                // 渐隐随出屏比例（入场渐显 × 拖拽渐隐）
                val width = if (widthPx.isNaN()) 1f else widthPx
                alpha = appear.value * (1f - (abs(drag) / width).coerceIn(0f, 1f))
            }
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = dragEnabled,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                    state = state,
                    // 40%（默认 50%）：START 侧松手点离屏缘更远——50% 阈值时松手点
                    // 落入 MIUI 返回手势区被截断（真机实证）
                    positionalThreshold = { it * 0.4f },
                    // v4：spring 接管松手后的平移（替代默认 tween 的匀速生硬感）
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            )
    ) { content() }
}

/**
 * #192 v4：贴边拉杆（自绘胶囊贴屏缘 + 独立命中区）。
 *
 * 视觉：5x42dp 半透明 secondaryContainer 胶囊贴 x=0（START）/ 屏宽（END），
 * 底部 18dp（拇指区）。命中区 26x60dp 边缘对齐（不透明不拦截列表滚动——仅水平拖拽
 * 消费）。D4 双通道：点按即恢复；向屏内拖（跟手），过半松手自动恢复+回弹。
 * 入场：从屏缘滑出渐显（与 FAB 出屏动画衔接）。D6：badge>0 叠小角标（内侧）。
 */
@Composable
internal fun FabEdgeTab(
    edge: FabEdge,
    badge: Int?,
    contentDescription: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // 向屏内拖出方向：START 缘右拖（+1），END 缘左拖（-1）；RTL 取反
    val pullSign = when (edge) {
        FabEdge.START -> if (rtl) -1f else 1f
        FabEdge.END -> if (rtl) 1f else -1f
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pullMaxPx = with(density) { 40.dp.toPx() }
    val state = remember { AnchoredDraggableState(FabSwipeAnchor.Visible) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(200)) }
    LaunchedEffect(pullMaxPx) {
        state.updateAnchors(
            DraggableAnchors {
                FabSwipeAnchor.Visible at 0f
                FabSwipeAnchor.Hidden at pullMaxPx * pullSign
            }
        )
    }
    // 拉过半松手 → 恢复 FAB；本组件随即离树，但仍显式回弹（防同帧残留）
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }
            .collect {
                if (it == FabSwipeAnchor.Hidden) {
                    onRestore()
                    scope.launch { state.animateTo(FabSwipeAnchor.Visible) }
                }
            }
    }
    Box(
        modifier
            .padding(bottom = 18.dp)
            .graphicsLayer {
                val off = state.offset
                val pull = if (off.isNaN()) 0f else off
                // 入场从屏外滑入：appear 0→1，起点向屏外偏移
                translationX = pull + (1f - appear.value) * -pullSign * 72f
                alpha = appear.value
            }
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Horizontal,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                    state = state,
                    positionalThreshold = { it * 0.5f },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ),
            )
            .clickable { onRestore() }
            .semantics { this.contentDescription = contentDescription }
            .size(width = 26.dp, height = 60.dp)
    ) {
        // 贴边胶囊：START 靠 x=0 / END 靠屏宽（命中区边缘对齐，胶囊视觉贴屏缘）
        Box(
            Modifier
                .align(if (edge == FabEdge.START) Alignment.CenterStart else Alignment.CenterEnd)
                .size(width = 5.dp, height = 42.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    RoundedCornerShape(percent = 50),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    RoundedCornerShape(percent = 50),
                )
        )
        if (badge != null && badge > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .align(if (edge == FabEdge.START) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = 2.dp),
            ) { Text(badge.coerceAtMost(99).toString()) }
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
 * #192 v4：[hidden]/[onHide]/[onRestore] 会话级滑动隐藏（单一连续平移动画）。
 * D5 两段式：展开期拖拽禁用，右划由外点收起层检测（水平累计 >40dp 收拢菜单）。
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

    // 外点收起层（仅展开时存在；无视觉、整屏拦截，画在菜单之下）。
    // D5：水平拖拽累计超 ~40dp 也收拢（展开期右划=收起，不隐藏不位移）
    if (expanded) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { expanded = false } }
                .pointerInput(Unit) {
                    var accumH = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { accumH = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            accumH += dragAmount
                            if (abs(accumH) > 80f) {
                                expanded = false
                                accumH = 0f
                            }
                        },
                    )
                }
        )
    }

    val totalBadge = stackedCount + todoPendingCount + agentRunningCount + shellRunningCount

    // align 挂本容器（直接子级才吃 ParentData）；展开期拖拽禁用（D5）
    SwipeHideFabContainer(
        modifier = modifier,
        dragSign = +1f,
        onHidden = onHide,
        dragEnabled = !expanded,
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
 * #192 v4：[hidden]/[onHide]/[onRestore]（单一连续平移动画）。
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
        SwipeHideFabContainer(modifier = modifier, dragSign = -1f, onHidden = onHide) {
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
