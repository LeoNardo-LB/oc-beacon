package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
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
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlin.math.roundToInt

/** 工具栏入口 id（沿用第十轮四入口独立 sheet 语义）。 */
internal enum class ChatToolbarEntry { TODO, AGENT, SHELL, GOAL }

/** 贴边滑动顶边距（#194 D1：上限 = 容器高 − 按钮高 − 此边距）。 */
internal val FabSlideTopMargin: Dp = 8.dp

/**
 * #194 D2 菜单内容几何（全静态，tap 瞬时可算，无 stagger/锚点竞态——M3 折叠态
 * 不放置 item 内容，坐标锚点在首次展开前不可用，故弃实测改常量推导）：
 * - item 高 44dp（本项目定值，见 FabMenuEntry）；
 * - item 间距 = M3 `FabMenuItemSpacingVertical` = `ListItemBetweenSpace` token = 4dp；
 * - 列底 padding = `FabMenuPaddingBottom` token = 8dp（CloseButtonBetweenSpace）。
 * 数值以 M3 1.5.0-alpha26 源码为准（真机 E4d 实测 shift=588px 与此推导精确吻合）。
 */
private val FabMenuItemHeight: Dp = 44.dp
private val FabMenuItemSpacingVertical: Dp = 4.dp
private val FabMenuPaddingBottomToken: Dp = 8.dp
private const val FabMenuItemCount = 5

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
    var offsetYPx by mutableFloatStateOf(0f)

    /** 容器实测高（layout 约束 maxHeight，#194 D1——取代旧整屏高 − 160dp 魔法数）。 */
    var containerHeightPx by mutableFloatStateOf(0f)
        private set

    /** 节点折叠态实测高（layout 时写入；展开溢出计算用，稳定量）。 */
    var collapsedNodeHeightPx by mutableFloatStateOf(0f)
        private set

    /** 容器尺寸变化（键盘/分屏）时对存量位移重新收界（D1：防陈值越界）。 */
    fun coerceOffset(maxUpPx: Float) {
        if (maxUpPx >= 0f) offsetYPx = offsetYPx.coerceIn(-maxUpPx, 0f)
    }

    /** 几何锚点写入（layout/onGloballyPositioned 实测回调，同模块内可写）。 */
    internal fun updateContainerHeight(px: Float) {
        if (px > 0f) containerHeightPx = px
    }

    internal fun updateCollapsedNodeHeight(px: Float) {
        if (px > 0f) collapsedNodeHeightPx = px
    }

    companion object
}

/** 位移持久化 Saver（只存 offsetYPx；几何锚点为瞬态，不参与保存）。 */
private val FabEdgeSlideSaver = Saver<FabEdgeSlideState, Float>(
    save = { it.offsetYPx },
    restore = { FabEdgeSlideState().apply { offsetYPx = it } },
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
            // 折叠态节点高 = **观测最小值**：展开/stagger 只会更大；收起动画收缩途中的
            // 瞬态高度不可作基准（否则 layout 收界把 offsetYPx 永久钳上去——E4e 二次实证）。
            // menuCollapsed 参数仅保留语义提示，基准计算不再依赖瞬时尺寸。
            if (state.collapsedNodeHeightPx == 0f ||
                placeable.height.toFloat() < state.collapsedNodeHeightPx
            ) {
                state.updateCollapsedNodeHeight(placeable.height.toFloat())
            }
            // D1 收界基准 = 稳定折叠高（键盘/分屏容器变化时收界；展开期间不误钳停放位）
            val maxUp = containerH - state.collapsedNodeHeightPx - marginPx
            state.coerceOffset(maxUp)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, (state.offsetYPx + extraShift()).roundToInt())
            }
        }
        .pointerInput(state) {
            detectVerticalDragGestures(
                onDragStart = { onDragStart() },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    // 上限基准 = 折叠态节点高（D4 合并后节点仍在收起动画中，尺寸未回落）
                    val maxUp = state.containerHeightPx.let { ch ->
                        val basis = state.collapsedNodeHeightPx
                        if (ch > 0f && basis > 0f) ch - basis - marginPx else 0f
                    }
                    state.offsetYPx = (state.offsetYPx + dragAmount).coerceIn(-maxUp, 0f)
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
 * shift 瞬时并入 offsetYPx（位置连续、不双计）。
 */
@Composable
internal fun ChatFabMenu(
    todoPendingCount: Int,
    agentRunningCount: Int,
    shellRunningCount: Int,
    /** 目标状态（#286）：goal.active/blocked → FAB 运行点 + 菜单项 phase 角标；null/complete 不渲染角标。 */
    goalPhase: String? = null,
    onOpenEntry: (ChatToolbarEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val slideState = rememberFabEdgeSlideState()

    // D2：展开溢出下移分量（临时态，不持久化）
    var expandShift by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val marginPx = with(density) { FabSlideTopMargin.toPx() }
    // 全静态菜单几何（Q3：tap 瞬时可算，无竞态）：span = N×44dp + (N−1)×4dp
    val menuSpanPx = with(density) {
        (FabMenuItemHeight * FabMenuItemCount +
            FabMenuItemSpacingVertical * (FabMenuItemCount - 1)).toPx()
    }
    val menuPadPx = with(density) { FabMenuPaddingBottomToken.toPx() }

    BackHandler(enabled = expanded) { expanded = false }

    // D3 动画编排（单一效应，键 = expanded：false→true 展开 / true→false 收起，
    // 再展开必然重触发——不存在同值目标不重启的问题）。
    // 展开：tap 瞬间算好目标，items 交错浮现的同时整体下滑就位（同时进行）；
    // 收起：items 消退的同时 expandShift 平滑回 0（offsetYPx 停放位保持不动）。
    LaunchedEffect(expanded) {
        if (expanded) {
            val target = computeFabExpandShiftPx(
                collapsedPx = slideState.collapsedNodeHeightPx,
                menuSpanPx = menuSpanPx,
                containerPx = slideState.containerHeightPx,
                offsetYPx = slideState.offsetYPx,
                menuPadPx = menuPadPx,
                topMarginPx = marginPx,
            )
            AppLogger.d(
                "ChatFabMenu",
                "[fab-shift] expand: collapsed=${slideState.collapsedNodeHeightPx.toInt()} " +
                    "span=${menuSpanPx.toInt()} H=${slideState.containerHeightPx.toInt()} " +
                    "offsetYPx=${slideState.offsetYPx.toInt()} -> shift=${target.toInt()}",
            )
            if (target > 0f) {
                animate(expandShift, target, animationSpec = tween(ExpandShiftAnimMs)) { v, _ ->
                    expandShift = v
                }
            }
        } else if (expandShift != 0f) {
            AppLogger.d("ChatFabMenu", "[fab-shift] collapse: ${expandShift.toInt()} -> 0")
            animate(expandShift, 0f, animationSpec = tween(ExpandShiftAnimMs)) { v, _ ->
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

    val totalBadge = todoPendingCount + agentRunningCount + shellRunningCount
    // #286：goal 运行点——active/blocked 态 FAB 角标（运行点）；菜单项角标按 phase 着色（blocked 警示）。
    val goalActive = goalPhase == "active" || goalPhase == "blocked"

    // 2026-08-27 稳定 API 复刻（material3 1.4.0）：官方 FloatingActionButtonMenu/
    // ToggleFloatingActionButton 是 1.5.0-alpha 专属 API（按 ui 1.12-beta 编译，
    // 与稳定 ui 组二进制冲突）——布局几何（items 上排/button 钉底/44dp 药丸/
    // 8dp 列底距）与 #194 溢出计算精确对齐原实现，morph 动画简化为整列展开。
    Box(
        modifier = modifier.fabEdgeVerticalSlide(
            state = slideState,
            extraShift = { expandShift },
            onDragStart = {
                if (expanded) {
                    // D4：展开中拖动 → 收起，当前 shift 瞬时并入 offsetYPx（位置连续、
                    // 不双计），此后拖动直接跟手；effect 重启时 expandShift 已为 0，
                    // 收起动画自然成为无操作（无双计）
                    AppLogger.d(
                        "ChatFabMenu",
                        "[fab-shift] drag-merge: offsetYPx=${slideState.offsetYPx.toInt()} " +
                            "+ shift=${expandShift.toInt()}",
                    )
                    expanded = false
                    slideState.offsetYPx += expandShift
                    expandShift = 0f
                }
            },
        ),
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(ExpandShiftAnimMs)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(ExpandShiftAnimMs)) + fadeOut(),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    FabMenuEntry(
                        icon = Icons.Default.Checklist,
                        label = stringResource(R.string.pending_tab_todo_plain),
                        count = todoPendingCount,
                        onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.TODO) },
                    )
                    Spacer(Modifier.height(FabMenuItemSpacingVertical))
                    FabMenuEntry(
                        icon = Icons.Default.AccountTree,
                        label = stringResource(R.string.toolbar_agent),
                        count = agentRunningCount,
                        onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.AGENT) },
                    )
                    Spacer(Modifier.height(FabMenuItemSpacingVertical))
                    FabMenuEntry(
                        icon = Icons.Default.Flag,
                        label = stringResource(R.string.toolbar_goal),
                        count = 0,
                        // #286：目标菜单项角标（phase 色；blocked 警示色 error）——
                        // complete/无 goal 不渲染角标（Web 语义：完成态不渲染条目）
                        badgeColor = when (goalPhase) {
                            "blocked" -> MaterialTheme.colorScheme.error
                            "active" -> MaterialTheme.colorScheme.primary
                            "paused" -> MaterialTheme.colorScheme.secondary
                            else -> null
                        },
                        onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.GOAL) },
                    )
                    Spacer(Modifier.height(FabMenuItemSpacingVertical))
                    FabMenuEntry(
                        icon = Icons.Default.Terminal,
                        label = stringResource(R.string.toolbar_shell),
                        count = shellRunningCount,
                        onClick = { expanded = false; onOpenEntry(ChatToolbarEntry.SHELL) },
                    )
                    // 列底距（FabMenuPaddingBottom token，与 #194 D2 溢出计算的
                    // menuPadPx 同源）：items 与 button 的间距
                    Spacer(Modifier.height(FabMenuPaddingBottomToken))
                }
            }
            FloatingActionButton(
                onClick = { expanded = !expanded }, // shift 计算在 LaunchedEffect(expanded) 内（Q3 瞬时稳定量）
                // 描边（第二十轮，用户要求）：角半径冻结 16dp——形状恒定描边才贴边
                modifier = Modifier
                    .size(48.dp)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                // Secondary 变体（第十九轮，用户选 B）：secondaryContainer 系，
                // 与用户气泡（primaryContainer 系）区分；展开态不 morph（稳定
                // API 无 checked 色彩过渡，图标切换承担状态表达）
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                val desc = if (expanded) {
                    stringResource(R.string.chat_fab_menu_close)
                } else {
                    stringResource(R.string.chat_fab_menu_open)
                }
                val fabIcon: @Composable () -> Unit = {
                    Icon(
                        if (expanded) Icons.Default.Close else Icons.Default.Inbox,
                        contentDescription = desc,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (!expanded && goalActive) {
                    // #286：goal 运行点角标（blocked 用警示色）——取代数字角标
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = if (goalPhase == "blocked") {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ) {}
                        }
                    ) { fabIcon() }
                } else if (!expanded && totalBadge > 0) {
                    BadgedBox(
                        badge = { Badge { Text(totalBadge.coerceAtMost(99).toString()) } }
                    ) { fabIcon() }
                } else {
                    fabIcon()
                }
            }
        }
    }
}

/** FAB 菜单入口项（M3 全默认：56dp primaryContainer 药丸/titleMedium/24dp 图标；角标挂 icon）。 */
/** FAB 菜单入口项（稳定 API 复刻：44dp 药丸 Surface + stadium 描边 + 角标挂 icon；
 *  几何与 #194 D2 的 FabMenuItemHeight 常量严格一致）。 */
@Composable
private fun FabMenuEntry(
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空 → 图标侧相位圆点角标（#286：目标 phase 色 / blocked 警示色）。 */
    badgeColor: Color? = null,
) {
    Surface(
        onClick = onClick,
        // 高度 44dp（官方 56dp，2026-08-23 用户指示 item 保持现状）+ stadium 描边（第二十轮）
        modifier = modifier
            .height(44.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
        shape = RoundedCornerShape(50),
        // Secondary 变体（第十九轮）：药丸 secondaryContainer 系，与用户气泡区分
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (count > 0) {
                BadgedBox(
                    badge = { Badge { Text(count.coerceAtMost(99).toString()) } }
                ) {
                    Icon(icon, contentDescription = null)
                }
            } else if (badgeColor != null) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = badgeColor) {}
                    }
                ) {
                    Icon(icon, contentDescription = null)
                }
            } else {
                Icon(icon, contentDescription = null)
            }
            Text(label)
        }
    }
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
                // 2026-08-29 用户裁决「双 FAB 均贴边无边距」：去 start=16dp——该值
                // 镜像的菜单按钮内部横距已随 08-27 稳定 API 复刻（按钮钉底贴边）
                // 消失，保留即左右不对称（左 16dp/右 0，真机截图实证）。
                .padding(bottom = 16.dp)
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