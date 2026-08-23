package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlin.math.abs
import kotlinx.coroutines.flow.drop

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/** #192 锚点值（Foundation 官方 AnchoredDraggable 引擎驱动）。 */
internal enum class FabSwipeAnchor { Visible, Peeking }

/**
 * v5 定案（2026-08-23，用户：「不是拉杆，而是贴边露出原图标 ~1/4 + 透明度变高」）：
 * - **Peek 模式**：隐藏 = FAB 本体滑至贴边锚点（留 [FabPeekVisible] 12dp ≈ 按钮的
 *   1/4）+ alpha 降至 [FabPeekAlpha] 0.35 驻留——同一组件同一动画系统，无切换
 *   无拉杆（v2–v4 的独立拉杆/FabEdgeTab 已全部移除）；
 * - **恢复双通道**：点 peek 出的角（tap 拦截层）即滑回；或向屏内拖（引擎锚点
 *   0↔peek，松手按阈值吸附）；
 * - **连续动画**：跟手平移 → 松手 spring 同方向到位（NoBouncy/MediumLow），
 *   透明度随位移比例插值——全程一套属性，无衔接断裂；
 * - v5c：dock 距离只用稳定量（容器宽 onSizeChanged + 布局常量 buttonEdgeInset），
 *   不再上报按钮坐标——按钮被进场/morph 动画每帧污染（76 条日志实证振荡），
 *   且以 dockPx 为 key 的 animateTo 每帧重启会抢占 drag mutex（滑不动根因）；
 * - 尺寸（用户 2026-08-23）：Toggle 48/52dp、左 FAB 48dp（菜单项保持 44dp）；
 * - D3：手动隐藏期「滚离底部自动出现」暂停（peek 驻留恒在）；
 * - D5：菜单展开期容器拖拽禁用，右划由外点收起层消费（只收菜单不滑走）。
 */

/** #192 peek 驻留时贴边露出的宽度（dp）≈ 48dp 按钮的 1/4。 */
internal val FabPeekVisible = 12.dp

/** #192 peek 驻留透明度。 */
internal const val FabPeekAlpha = 0.35f

/**
 * #192 v5c：FAB 滑动隐藏容器（Peek 模式，稳定 dock 模型）。
 *
 * [dragSign] 隐藏方向物理符号（end 侧 +1 向右 / start 侧 -1 向左）。
 * [hidden] 状态驱动锚点目标（true→Peeking / false→Visible，spring 过渡）。
 * [buttonEdgeInset] 按钮贴 dock 侧边缘距容器 dock 侧边缘的距离（菜单 FAB 内部
 * padding 16dp；左 FAB 按钮右缘即容器右缘 = 0dp）。
 *
 * dock = (W − inset − peek) × dragSign：恰留 peek 的按钮本体贴屏缘。
 *
 * align 挂载点（教训保持）：align 是 ParentDataModifier 只对直接父 Box 生效——
 * [modifier]（含 BottomStart/BottomEnd）挂本容器（ChatScreen Box 直接子级）。
 */
@Composable
private fun SwipeHideFabContainer(
    modifier: Modifier = Modifier,
    dragSign: Float,
    hidden: Boolean,
    onHide: () -> Unit,
    onRestore: () -> Unit,
    dragEnabled: Boolean = true,
    buttonEdgeInset: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val peekPx = with(LocalDensity.current) { FabPeekVisible.toPx() }
    val insetPx = with(LocalDensity.current) { buttonEdgeInset.toPx() }
    val state = remember { AnchoredDraggableState(FabSwipeAnchor.Visible) }
    var widthPx by remember { mutableStateOf(Float.NaN) }
    var dockPx by remember { mutableStateOf(Float.NaN) }
    // 进场：从 dock 侧滑入 + 渐显
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    LaunchedEffect(peekPx, insetPx) {
        snapshotFlow { widthPx }.collect { w ->
            if (!w.isNaN()) {
                val d = (w - insetPx - peekPx) * dragSign
                if (abs(d - dockPx) > 0.5f) {
                    dockPx = d
                    state.updateAnchors(
                        DraggableAnchors {
                            FabSwipeAnchor.Visible at 0f
                            FabSwipeAnchor.Peeking at d
                        }
                    )
                }
            }
        }
    }
    // 状态驱动：仅 hidden 翻转时 spring 到目标锚点（key 绝不含 dockPx——防 mutex 抢占）
    LaunchedEffect(hidden) {
        if (!dockPx.isNaN()) {
            state.animateTo(
                if (hidden) FabSwipeAnchor.Peeking else FabSwipeAnchor.Visible,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }
    // 手势 settle 到 Peeking → 通知上层置 hidden（幂等）；drop(1) 防初始 emit
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }
            .drop(1)
            .collect { if (it == FabSwipeAnchor.Peeking) onHide() }
    }
    // settle 回 Visible → 通知上层复位（拖拽恢复通道）
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }
            .drop(1)
            .collect { if (it == FabSwipeAnchor.Visible) onRestore() }
    }
    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .graphicsLayer {
                // 首帧守卫：NaN（updateAnchors 未派发）视为 0（真机 FATAL 实证过）
                val off = state.offset
                val drag = if (off.isNaN()) 0f else off
                val dock = if (dockPx.isNaN()) 1f else dockPx
                translationX = drag + (1f - appear.value) * dock
                // 透明度：进场渐显 × 随位移比例衰减至 peek 驻留值
                val progress = (abs(drag) / abs(dock)).coerceIn(0f, 1f)
                alpha = appear.value * (1f - (1f - FabPeekAlpha) * progress)
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
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            )
    ) {
        content()
        // Peek 驻留时的 tap 拦截层：吃掉点击 → 恢复（不透传给 FAB 的 onClick）
        if (state.currentValue == FabSwipeAnchor.Peeking) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable { onRestore() }
            )
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
 * #192 v5c：[hidden]/[onHide]/[onRestore] Peek 模式（贴边 1/4 + 半透明驻留）。
 * D5 两段式：展开期容器拖拽禁用，右划由外点收起层消费（仅收拢菜单）。
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

    // 外点收起层（仅展开时存在；无视觉、整屏拦截，画在菜单之下）。
    // D5：水平拖拽累计超 ~27dp 也收拢（展开期右划=收起，不隐藏不位移）
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

    SwipeHideFabContainer(
        modifier = modifier,
        dragSign = +1f,
        hidden = hidden,
        onHide = onHide,
        onRestore = onRestore,
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
                    // 尺寸（2026-08-23 用户「按钮再大一些，item 不变」）：48→展开 52dp；
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
        // 高度 44dp（官方 56dp，用户 2026-08-23 指示 item 保持现状）+ stadium 描边（第二十轮）
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
 * 与菜单 FAB 同规格：48dp/圆角 16dp/secondaryContainer/1dp outline 描边/
 * 24dp 图标 onSecondaryContainer tint（2026-08-23 用户「按钮再大一些」44→48dp）。
 *
 * 一致性关键（第二十一轮实测修复）：普通 FloatingActionButton 内部强制
 * LocalMinimumInteractiveComponentSize(48dp) 最小触达——provision 0dp 关闭强制
 * （FloatingActionButtonMenuItem 源码同款手法），双圆严格同径（Toggle 48dp 不吃该机制）。
 * isAtBottom 的 .value 读取限制在本函数小作用域（B-F5 重组隔离沿袭）。
 *
 * #192 v5c Peek 模式 + D3：手动隐藏优先——hidden 期间 peek 驻留恒在（不因回底
 * 消失）；仅未隐藏时保留「在底部自动隐藏」原语义。
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
    // D3：未手动隐藏时保留原「在底部不显示」；隐藏（peek）期间恒驻留
    if (!hidden && isAtBottomState.value) return
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SwipeHideFabContainer(
            modifier = modifier,
            dragSign = -1f,
            hidden = hidden,
            onHide = onHide,
            onRestore = onRestore,
            // 左 FAB 按钮右缘即容器右缘（无 end padding）→ dock 侧 inset = 0
            buttonEdgeInset = 0.dp,
        ) {
            FloatingActionButton(
                onClick = onClick,
                // 16dp 底距 = 菜单内部按钮下距，双 FAB 同基线
                modifier = Modifier
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
}
