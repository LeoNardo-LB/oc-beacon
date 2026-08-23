package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlin.math.roundToInt

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { STACKED, TODO, AGENT, SHELL }

/** 贴边滑动顶边距（#194 D1：上限 = 容器高 − 按钮高 − 此边距）。 */
internal val FabSlideTopMargin: Dp = 8.dp

/**
 * #194 D1 展开溢出量计算（纯函数，单测覆盖）——全稳定量版（无 stagger 竞态）。
 *
 * 几何（M3 FloatingActionButtonMenu 源码证实）：菜单节点 bottom 对齐容器底，
 * button 钉在节点底 −16dp，items 从节点顶下排。完全展开高
 * `expandedPx = collapsedPx + menuSpanPx + menuPadPx`：
 * - collapsedPx = 节点折叠态实测高（button 区）；
 * - menuSpanPx = item0 顶 ↔ 末 item 底的 **root 坐标差**——items 相对位置在 stagger
 *   入场动画中不变（M3 布局无条件按最终 y 序放置），任意时刻实测同值，tap 瞬时可算；
 * - menuPadPx = FabMenuPaddingBottom token（8dp）。
 *
 * items 顶缘越过容器顶的量 `= expandedPx − containerPx − offsetYPx`（≤0 = 无溢出）。
 * 溢出时整体下移「溢出量 + 顶边距」（「顶到顶部」语义，spec 2026-08-23 D2/Q10）；
 * 空间恰好够时返回 0——items 自然达顶，与溢出路径在临界点几何一致，无模式跳变。
 */
internal fun computeFabExpandShiftPx(
    collapsedPx: Float,
    menuSpanPx: Float,
    containerPx: Float,
    offsetYPx: Float,
    menuPadPx: Float,
    topMarginPx: Float,
): Float {
    val expandedPx = collapsedPx + menuSpanPx + menuPadPx
    val overflow = expandedPx - containerPx - offsetYPx
    return if (overflow > 0f) overflow + topMarginPx else 0f
}

/**
 * 贴边滑动状态（#194 D1）：位移持久化 + 容器/锚点几何（瞬态，实测写入）。
 * 双 FAB 各持独立实例（D5：位移互不影响）。
 */
@Stable
internal class FabEdgeSlideState {
    /** 纵向位移（负 = 上移；0 = 底部原位）。rememberSaveable 持久化（Saver 只存此项）。 */
    var offsetY by mutableFloatStateOf(0f)

    /** 容器实测高（layout 约束 maxHeight，#194 D1——取代旧整屏高 − 160dp 魔法数）。 */
    var containerHeightPx by mutableFloatStateOf(0f)
        private set

    /** 节点折叠态实测高（layout 时写入；展开溢出计算用，稳定量）。 */
    var collapsedNodeHeightPx by mutableFloatStateOf(0f)
        private set

    /** 首 item 顶 / 末 item 底（root 坐标）——两者差 = 菜单内容高（stagger 不变量）。 */
    var firstItemTopInRoot by mutableFloatStateOf(Float.MAX_VALUE)
        private set

    var lastItemBottomInRoot by mutableFloatStateOf(Float.MIN_VALUE)
        private set

    /** 菜单内容高（item0 顶 ↔ 末 item 底；两锚点齐备前为 0）。 */
    val menuSpanPx: Float
        get() = if (firstItemTopInRoot == Float.MAX_VALUE || lastItemBottomInRoot == Float.MIN_VALUE) {
            0f
        } else {
            (lastItemBottomInRoot - firstItemTopInRoot).coerceAtLeast(0f)
        }

    /** 容器尺寸变化（键盘/分屏）时对存量位移重新收界（D1：防陈值越界）。 */
    fun coerceOffset(maxUpPx: Float) {
        if (maxUpPx >= 0f) offsetY = offsetY.coerceIn(-maxUpPx, 0f)
    }

    /** 几何锚点写入（layout/onGloballyPositioned 实测回调，同模块内可写）。 */
    internal fun updateContainerHeight(px: Float) {
        if (px > 0f) containerHeightPx = px
    }

    internal fun updateCollapsedNodeHeight(px: Float) {
        if (px > 0f) collapsedNodeHeightPx = px
    }

    internal fun updateFirstItemTopInRoot(px: Float) {
        firstItemTopInRoot = px
    }

    internal fun updateLastItemBottomInRoot(px: Float) {
        lastItemBottomInRoot = px
    }

    companion object
}

/** 位移持久化 Saver（只存 offsetY；几何锚点为瞬态，不参与保存）。 */
private val FabEdgeSlideSaver = Saver<FabEdgeSlideState, Float>(
    save = { it.offsetY },
    restore = { FabEdgeSlideState().apply { offsetY = it } },
)

@Composable
internal fun rememberFabEdgeSlideState(): FabEdgeSlideState =
    rememberSaveable(saver = FabEdgeSlideSaver) { FabEdgeSlideState() }

/**
 * #192 v6 + #194 D1：FAB 沿所在屏缘垂直拖动（贴边上下滑动）。
 * - 拖动跟随手指（placeRelative 位移，命中区同步移动），松手即停在原处；
 * - 上限 = **容器实测高**（本节点 layout 约束 maxHeight）− 节点高 − 8dp 顶边距
 *   （#194 根修：旧「整屏高 − 160dp」坐标系错位致拖到顶钻进顶栏，魔法数已移除）；
 * - 下限 0（回到底部原位）；容器尺寸变化时对存量位移重新收界；
 * - 位移 rememberSaveable（返回栈弹出/进程重启复位由调用方 Saver 承担）；
 * - 点击语义不变（过 touch slop 才消费，tap 照常）；
 * - [extraShift] 展开溢出下移分量（仅菜单 FAB 使用，D2）；[onDragStart] 供展开中
 *   拖动先收起并入位移（D4）。
 */
private fun Modifier.fabEdgeVerticalSlide(
    state: FabEdgeSlideState,
    menuCollapsed: () -> Boolean = { true },
    extraShift: () -> Float = { 0f },
    onDragStart: () -> Unit = {},
): Modifier = composed {
    val density = LocalDensity.current
    val marginPx = with(density) { FabSlideTopMargin.toPx() }
    this
        .layout { measurable, constraints ->
            val containerH = constraints.maxHeight.toFloat()
            if (containerH > 0f && containerH != state.containerHeightPx) {
                state.updateContainerHeight(containerH)
            }
            val placeable = measurable.measure(constraints)
            // 折叠态节点高 = 稳定量（展开态随 stagger 动画增长，不可作锚点）
            if (menuCollapsed()) state.updateCollapsedNodeHeight(placeable.height.toFloat())
            val maxUp = containerH - placeable.height - marginPx
            state.coerceOffset(maxUp) // D1：键盘/分屏等容器变化后收界（有界写入，收敛）
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, (state.offsetY + extraShift()).roundToInt())
            }
        }
        .pointerInput(state) {
            detectVerticalDragGestures(
                onDragStart = { onDragStart() },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    val maxUp = state.containerHeightPx.let { ch ->
                        if (ch > 0f) ch - this.size.height - marginPx else 0f
                    }
                    state.offsetY = (state.offsetY + dragAmount).coerceIn(-maxUp, 0f)
                },
            )
        }
}

/** 展开下移动画时长（D3：与官方展开节奏对齐 ~300ms，平滑无闪现）。 */
private const val ExpandShiftAnimMs = 300

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
 * #194 D2–D4：高位展开时菜单溢出量整体平滑下移（expandShift 临时态，不持久化）——
 * 只在点击展开那一瞬间实测计算一次；收起动画回 0；展开中拖动则先收起并把
 * shift 瞬时并入 offsetY（位置连续、不双计）。
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
    val slideState = rememberFabEdgeSlideState()

    // D2：展开溢出下移分量（临时态）——NaN 目标 = 空闲（无待执行动画）
    var expandShift by remember { mutableFloatStateOf(0f) }
    var shiftTarget by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val marginPx = with(density) { FabSlideTopMargin.toPx() }
    // FabMenuPaddingBottom token（M3 1.5.0-alpha26 = 8.dp）：完全展开高 = 折叠高 +
    // menuSpan + 此值（items 列底 padding）——全稳定量，tap 瞬时可算（Q3）
    val menuPadPx = with(density) { 8.dp.toPx() }

    BackHandler(enabled = expanded) { expanded = false }

    // D3 收起：items 消退的同时 expandShift 平滑回 0（offsetY 停放位保持不动）
    LaunchedEffect(expanded) {
        if (!expanded && expandShift != 0f) {
            animate(expandShift, 0f, animationSpec = tween(ExpandShiftAnimMs)) { v, _ ->
                expandShift = v
            }
        }
    }

    // D3 展开：items 交错浮现的同时整体下滑就位（首帧实测后启动，同时进行）
    LaunchedEffect(shiftTarget) {
        val target = shiftTarget
        if (!target.isNaN() && target != expandShift) {
            animate(expandShift, target, animationSpec = tween(ExpandShiftAnimMs)) { v, _ ->
                expandShift = v
            }
        }
    }

    // 外点收起层（仅展开时存在；无视觉、整屏拦截，画在菜单之下；不随平移变化）
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
        modifier = modifier.fabEdgeVerticalSlide(
            state = slideState,
            menuCollapsed = { !expanded },
            extraShift = { expandShift },
            onDragStart = {
                if (expanded) {
                    // D4：展开中拖动 → 收起，当前 shift 瞬时并入 offsetY（位置连续、
                    // 不双计），此后拖动直接跟手；取消待执行的展开动画
                    expanded = false
                    slideState.offsetY += expandShift
                    expandShift = 0f
                    shiftTarget = Float.NaN
                }
            },
        ),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = {
                    if (it) {
                        // Q3：tap 瞬间一次性计算（全稳定量：折叠高/menuSpan/容器高/停放位移
                        // 均与 stagger 入场动画无关，无竞态）
                        val target = computeFabExpandShiftPx(
                            collapsedPx = slideState.collapsedNodeHeightPx,
                            menuSpanPx = slideState.menuSpanPx,
                            containerPx = slideState.containerHeightPx,
                            offsetYPx = slideState.offsetY,
                            menuPadPx = menuPadPx,
                            topMarginPx = marginPx,
                        )
                        shiftTarget = if (target > 0f) target else Float.NaN
                        expanded = true
                    } else {
                        expanded = false
                    }
                },
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
            // D2 溢出锚点：首 item 顶缘（stagger 不变量——items 相对位置入场动画中不变）
            modifier = Modifier.onGloballyPositioned { coords ->
                slideState.updateFirstItemTopInRoot(coords.boundsInRoot().top)
            },
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
            // D2 溢出锚点：末 item 底缘（与首 item 顶缘差 = 菜单内容高）
            modifier = Modifier.onGloballyPositioned { coords ->
                slideState.updateLastItemBottomInRoot(coords.boundsInRoot().bottom)
            },
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
    modifier: Modifier = Modifier,
) {
    FloatingActionButtonMenuItem(
        onClick = onClick,
        // 高度 44dp（官方 56dp，2026-08-23 用户指示 item 保持现状）+ stadium 描边（第二十轮）
        modifier = modifier
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
 * #194 D5：共用修好上限的滑动（容器实测高收界），位移与菜单 FAB 各自独立。
 */
@Composable
internal fun ChatScrollBottomFab(
    isAtBottomState: State<Boolean>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isAtBottomState.value) return // 在底部时不显示
    val slideState = rememberFabEdgeSlideState()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FloatingActionButton(
            onClick = onClick,
            // 16dp 底距 = 菜单内部按钮下距（FabMenuButtonPaddingBottom），双 FAB 同基线
            modifier = modifier
                .fabEdgeVerticalSlide(state = slideState)
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
