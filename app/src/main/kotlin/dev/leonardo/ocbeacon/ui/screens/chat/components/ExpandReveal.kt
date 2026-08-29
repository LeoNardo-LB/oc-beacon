package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * #241 统一展开/收起过渡（2026-08-28 用户裁决：全部从上到下）——
 * 顶部锚定、高度向下生长/向上收回 + 淡入淡出。所有展开收起面共用，
 * 禁止单面自定义方向（防斜向/左上右下等不一致观感）。
 */
val ExpandEnterTransition: EnterTransition =
    fadeIn(animationSpec = tween(200)) + expandVertically(
        expandFrom = Alignment.Top,
        // 2026-08-30 下跳定音：spring 默认 spec 首帧/末帧各有一次 ~30px 突跳
        // （真机逐帧 trace 实测）。根因是分隔线固定尺寸在收缩容器里的布局
        // 硬地板（已根修：分隔线移入 body 滚动 Column 内部，滚动容器吸收
        // 任意约束）——地板消除后 M3 标准 FastOutSlowIn 缓动尾部平滑，
        // 200ms 时长 120Hz/60Hz 帧数整除。
        animationSpec = tween(200, easing = FastOutSlowInEasing),
    )

val ExpandExitTransition: ExitTransition =
    fadeOut(animationSpec = tween(200)) + shrinkVertically(
        shrinkTowards = Alignment.Top,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
    )

// 2026-08-30 文本主导卡的无 fade 变体（用户观察「思考卡展开没有动画」）：
// 纯文本 + 浅色无边界容器上，fadeIn 的透明度渐变掩盖了几何生长（10x 动画
// 帧序实证：文字半透明渐显被感知为「直接出现」）。去 fade 后文字清晰随
// 卡片生长逐行揭示，几何动画明确。结构化块卡（按钮/代码块/表格）保留
// 原 spec（块边缘给出几何线索，fade 不掩盖）。
val ExpandEnterNoFadeTransition: EnterTransition =
    expandVertically(
        expandFrom = Alignment.Top,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
    )

val ExpandExitNoFadeTransition: ExitTransition =
    shrinkVertically(
        shrinkTowards = Alignment.Top,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
    )

/** 会话 LazyListState 下传通道（#241 渲染前补偿用）：ChatMessageList 在列表
 *  内容处 provide，展开型组件就地 consume——避免穿 3-5 层签名的模板代码。 */
val LocalChatListState = compositionLocalOf<LazyListState?> { null }

/**
 * #241 展开增量渲染前补偿（一次性延迟揭示，#222 延迟揭示家族同语义）。
 *
 * 用户硬约束（2026-08-27 裁决）：展开把标签行向上顶出视口可以接受为正常行为，
 * **除非**能像流式补偿那样在渲染前移动视窗——事后 animateScrollBy 的可见
 * 滚动动画即「渲染后补偿」，不要。
 *
 * 机制（与 DeferredRevealCompensator 同构，单发简化版）：
 * - 增长遍（tap 展开后首遍测量）：真实高度 = 基线+Δ → 本遍只上报基线（多余
 *   部分被外层 clipToBounds 裁掉，未补偿几何**永不被放置**），同时经
 *   PreRenderShiftChannel.enqueue(+Δ) 入帧界队列——「内容生长 delta 即等量
 *   下移」：帧边界排空注入 request-position 待定位，视窗在下一帧**measure
 *   遍首**应用时下移 Δ（#258 换道：不再反射直写 scrollToBeConsumed，
 *   drag 竞态崩溃根因拆除，视觉时序逐帧不变）；
 * - 揭示遍：遍首待定位置已应用（锚点位移先就位），本遍全量上报真实高度——
 *   揭示与视窗位移严格同遍配对。全程无可见滚动动画。
 *
 * 用法（EventCard 根）：`Modifier.clipToBounds().expandRevealCompensation(...)`
 * ——clip 必须在本 layout **外层**（铁律同 #232）。
 */
internal class ExpandRevealCompensator {
    /** 配对版本号：注入使本节点失效 → 消费遍必然重测（与流式补偿同法）。 */
    var version by mutableStateOf(0)
        private set

    /** 已上报且视窗位移已配对的基准高度。 */
    private var reportedBase = 0

    /** 已注入、待下一遍消费后揭示的增量（可负=收缩上移）。 */
    private var pendingReveal = 0

    /** 是否已测过（区分「条目首次进入视口」与「就地展开」：前者直接全量上报，
     *  后者从 0 基准起全程配对——首帧部分测量（如 Markdown 异步占位）也要
     *  配对，否则首个增量漏注入 = 净漂移，2026-08-27 思考块 -18px 实证）。 */
    private var everMeasured = false

    /** 收起方向视窗分担比例（2026-08-30 用户裁决「下方收上来」的观感软化）：
     *  余量充足 = 1f（下方全收、上方静止）；余量不足 = 余量/收起总量（<1），
     *  下方与上方**全程按比例同步**收拢/下压——消除「撞底后上方突然接管」
     *  的切换点（用户报告的「有几率往下移动一小段」）。展开方向不缩放。 */
    private var collapseScale = 1f

    /**
     * 每遍测量调用。返回 (本遍上报高度, 注入下移量)。
     *
     * **链式逐帧配对**（泛化版）：每遍把「上一遍注入（已在遍首消费）」对齐
     * 揭示（report = 基准+待揭示），本遍新增长/收缩增量继续注入递延——
     * 单帧瞬变（tap 即时展开，两遍配对）与多帧动画（AnimatedVisibility
     * spring 高度、逐帧小增量）同一机制通吃；展开 +Δ 视窗下移、收起 -Δ
     * 视窗上移，位移全部发生在渲染前（用户硬约束 2026-08-27）。
     *
     * @param shiftApplied 运输层无未落地注入（PreRenderShiftChannel.shiftSettled）。
     *   同帧重测竞态门：注入入队后若被同帧插队重测（如拖动 forceRemeasure），
     *   此前会误判「增量已应用」提前揭示——揭示先于位移 = 跳变；门关闭时
     *   保持基准裁剪，揭示严格等位移落地。
     */
    fun onMeasure(realHeight: Int, shiftApplied: Boolean, anchoredAtBottom: Boolean = false, collapseRoomPx: Int = Int.MAX_VALUE): Pair<Int, Int> {
        // 条目首次进入视口（此前从未测过）：直接全量上报，绝不注入——
        // 否则新出现的展开卡会先隐身一帧再跳入。real==0（收起态）**不短路**：
        // 走通用配对（持有旧高 + 注入 -旧高），否则收起裸跟随 = 下坠回归
        // （2026-08-27 思考块 +369 复现实证）。
        if (!everMeasured) {
            everMeasured = true
            reportedBase = realHeight
            return realHeight to 0
        }
        // #sendgap 第二层（2026-08-29 整卡闪烁实证，H=332→290→253→332）：
        // 锚定在底时任何 hold/揭示循环都表现为整卡高度抖动——底部锚定下
        // 自然延伸即正确，补偿层直接透传真实高度（无注入、无保持、无循环）。
        // mid-list（off 被顶大 ≥100）不走此路径，补偿语义原样保留。
        if (anchoredAtBottom && realHeight <= reportedBase) {
            // 贴底收起/稳定：透传（上方内容承担位移——视口已贴列表尾，下方无
            // 收拢余量，dispatchRawDelta 消费 0，注入与透传等价）。
            pendingReveal = 0
            reportedBase = realHeight
            return realHeight to 0
        }
        // 贴底展开（realHeight > reportedBase）不再透传：走配对注入 → drain
        // pre-shift 视窗预移（用户 2026-08-30 裁决「贴底展开不要把内容往上
        // 顶」= 上方内容固定、卡片向下生长揭示；视口脱离贴底，收起自然回归）。
        // 2026-08-30 收起语义定音（用户裁决「下面的内容收上来，而不是整体
        // 收下去」）：收起注入 = 视窗沿贴底方向推 Δ（把下方内容收上来、上方
        // 内容固定、header 不动）——原生锚定的「上方整体下压」被本注入推翻。
        // 撞底（余量不足）时由 drain 钳到贴底，下方尽可能多地收上来，剩余由
        // 上方平滑承担（AV 逐帧，物理守恒无法归零）。

        val revealHeight = reportedBase + pendingReveal
        val extra = realHeight - revealHeight
        // 收起开始帧（首现收缩）按余量定分担比例：下方最多收 collapseRoomPx，
        // 剩余由上方按 (1-scale) 全程同步下压——无撞底切换点，观感为上下向
        // 中间柔和收拢。余量充足时 scale=1（下方全收、上方静止）。展开/稳定
        // 帧重置为 1f。
        if (realHeight < reportedBase && pendingReveal == 0) {
            collapseScale = (collapseRoomPx.toFloat() / reportedBase).coerceIn(0f, 1f)
        } else if (realHeight >= reportedBase) {
            collapseScale = 1f
        }
        return if (extra != 0) {
            val injected = if (extra < 0) (extra * collapseScale).toInt() else extra
            pendingReveal += injected
            version++
            revealHeight to injected
        } else if (pendingReveal != 0 && !shiftApplied) {
            // 竞态门：上一遍增量已入队但位移未落地（同帧重测插队）。
            // 展开侧（pending>0）：保持基准裁剪，杜绝「揭示先于位移」跳变
            // （2026-08-27 思考块裁决，不动）。
            // 收起侧（pending<0）：**禁止钳回 base**——本分支 extra==0 即
            // revealHeight==realHeight，上报真实尺寸几何恒正确；若钳 base，
            // 动画末帧恰落本分支时之后再无失效源，item 永久冻结在展开高度
            // = 收起后整块空白（2026-08-29 用户报告，[DEBUG-evcol] 迹线
            // hold report=1129 pending=-881 实证）。提前 ≤1 帧揭示收缩是
            // 有界瞬态，冻结是无界缺陷。
            if (pendingReveal < 0) revealHeight to 0 else reportedBase to 0
        } else {
            pendingReveal = 0
            reportedBase = realHeight
            realHeight to 0
        }
    }
}

/**
 * [ExpandRevealCompensator] 的 layout 包装（与 deferredRevealCompensation 同构）。
 * 铁律：必须与 clipToBounds 同链且 clip 在本 layout 外层。
 */
internal fun Modifier.expandRevealCompensation(
    listState: LazyListState,
    compensator: ExpandRevealCompensator,
    logTag: String,
): Modifier = this.layout { measurable, constraints ->
    // 订阅版本号：注入 → 本节点失效 → 消费遍重测（配对闭环，见流式补偿同注）
    require(compensator.version >= 0)
    val placeable = measurable.measure(
        constraints.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
    )
    val realHeight = placeable.height
    // #258 门 A 反馈修复：滚动中照常配对揭示（不再 holdReveal 裁剪）——通道②
    // 对 drag 无断言，旧通道「滚动中注入=崩溃放大器」前提已消失；默认展开卡的
    // 真实高度（markdown 异步解析）在滚动中即时配对到位，不再「滑完才展开」。
    val anchoredAtBottom = listState.firstVisibleItemIndex == 0 &&
        listState.firstVisibleItemScrollOffset < 100
    val (reportHeight, injectDelta) = compensator.onMeasure(
        realHeight,
        shiftApplied = PreRenderShiftChannel.shiftSettled(listState),
        anchoredAtBottom = anchoredAtBottom,
    )
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        AppLogger.d(
            "ExpandReveal",
            logTag + " trace real=" + realHeight + " report=" + reportHeight +
                " inject=" + injectDelta + " atBot=" + anchoredAtBottom
        )
    }
    if (injectDelta != 0) {
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.w(
                "ExpandReveal",
                logTag + " real=" + realHeight + " report=" + reportHeight +
                    " inject=" + injectDelta
            )
        }
        // #258 换道：帧界排队（下一帧 measure 遍首经 request-position 通道应用），
        // 不再反射直写 scrollToBeConsumed——drag 竞态崩溃根因拆除。
        PreRenderShiftChannel.enqueue(listState, injectDelta.toFloat())
    }
    layout(placeable.width, reportHeight) {
        placeable.placeRelative(0, 0)
    }
}
