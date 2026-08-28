package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * SSE 流式期间高度补偿的可变状态。
 * 跟踪上次测量的高度以及是否应应用补偿。
 */
internal class CompensateState {
    var lastHeight: Int = 0
    var shouldCompensate: Boolean = false
}

/**
 * #222 修二强化（真·渲染前，2026-08-25 用户定音）：延迟揭示状态机。
 *
 * 旧模式（request-position / scrollToBeConsumed 直注）的残余缺陷：注入发生在
 * 「增长已测出的那一遍」测量中途，靠下一遍消费——补救遍若未落在同帧（帧预算
 * 紧张 / markdown 迟到解析的巨跳变），一帧未补偿画面被绘制 → 用户感知
 * 「渲染后补偿」跳变。
 *
 * 延迟揭示把时序改成**构造性渲染前**（#258 换道后注入走 PreRenderShiftChannel：
 * 帧界排队 → 下一帧 measure 遍首经 request-position 通道应用，机制详见该类头）：
 * - 增长遍（pass k）：不向 LazyList 上报新高度（report 保持已消费基准），
 *   增量入帧界队列（下一帧 measure 遍首应用）；
 *   未上报的高度被 clipToBounds 裁掉——未补偿的几何**永不被放置**。
 * - 揭示遍（pass k+1，poke 加速到同帧）：遍首已应用增量（锚点位移先就位），
 *   本遍上报「基准+已消费增量」——揭示与锚点位移几何严格对齐。
 * - 连续增长链式：每遍揭示上一遍的增量、递延本遍的——最新一行文本至多
 *   晚一遍出现（同帧则零延迟），位置永不跳。
 *
 * 高度来源仍是精确测量（非预测），只是把「测量→注入→揭示」排序为
 * **消费先于揭示**——这满足「渲染前计算」的最强语义：任何被绘制的状态
 * 都已带着等量锚点位移。
 */
internal class DeferredRevealCompensator {
    /** 配对版本号（#225 修复）：注入时自增；layout 块读它建立快照订阅——
     *  注入使**本节点**测量失效，消费遍（scrollToBeConsumed 遍首消费的那遍）
     *  必然重新测量本节点 → 揭示与消费严格同遍配对。
     *
     *  修复背景（2026-08-25 真机像素取证）：注入只 poke 列表测量作用域时，
     *  消费遍会**复用本节点的缓存测量**（内容未变、约束未变 → Compose 跳过
     *  重测）——消费位移生效而揭示未更新 → 内容下跳一个注入单位（实测帧序
     *  +66px 恰等于单次 inject），下次内容刷新再揭示回弹 → 来回跳动。 */
    var version by androidx.compose.runtime.mutableStateOf(0)
        private set

    /** 已向 LazyList 上报且其对应注入已被遍首消费的基准高度。 */
    var reportedHeight: Int = 0
        private set

    /** 已入帧界队列、等待下一帧 measure 遍首应用的累计增量。 */
    var injectedPending: Int = 0
        private set

    /** 单次测量决策：上报高度 / 需注入增量 / 是否 poke 加速揭示。 */
    data class Decision(
        val reportHeight: Int,
        val injectDelta: Int,
        val poke: Boolean,
    )

    /**
     * 每遍测量调用。前件：调用即一遍——本次遍首已应用掉上次调用入队的
     * [injectedPending]（request-position 待定位在 measure 遍首应用，
     * LazyListMeasure 标准流程），故「[reportedHeight] + [injectedPending]」
     * 是与当前锚点位移几何一致的揭示高度。
     */
    /** 归零（item 消失/条件关闭时）：基准与欠账清空（遗留消费由边界钳制兜底）。 */
    fun reset() {
        reportedHeight = 0
        injectedPending = 0
    }
    fun onMeasure(
        realHeight: Int,
        shouldCompensate: Boolean,
        holdReveal: Boolean = false,
        shiftApplied: Boolean = true,
    ): Decision {
        // 冷启动：首测直接全量上报（无补偿语境，锚定几何自洽）
        if (reportedHeight <= 0) {
            reportedHeight = realHeight
            return Decision(realHeight, 0, false)
        }
        // 滚动保持（2026-08-27 #239 竞态修复当日二审纠偏）：滚动中**既不注入也
        // 不全揭示**——注入会让 scrollToBeConsumed 残量撞上 fling/drag 的
        // scrollBy（契约违规→防御等帧=顿挫，受控基线 24 次/10 fling）；全揭示
        // 则增长无配对注入 = 内容被顶起（「fling 中随流输出上推」回归实证）。
        // 第三条路：配对揭示上一遍已消费的注入（若有），本遍真实增长交由
        // clipToBounds 裁剪保持不可见——视口零位移。滚动静止后（调用方在
        // measure 块内读 isScrollInProgress 的订阅沿下降沿自动触发复测）累计
        // 增长走常规「注入→揭示」配对恢复。
        if (holdReveal) {
            val pairedReveal = reportedHeight + injectedPending
            reportedHeight = pairedReveal
            injectedPending = 0
            return Decision(pairedReveal, 0, false)
        }
        // #258 竞态门：增量已入队但位移未落地（同帧重测插队，如拖动
        // forceRemeasure）——保持基准裁剪，杜绝「揭示先于位移」跳变。
        if (injectedPending != 0 && !shiftApplied) {
            return Decision(reportedHeight, 0, false)
        }
        val revealHeight = reportedHeight + injectedPending
        val extra = realHeight - revealHeight
        if (!shouldCompensate) {
            // 贴底/用户回底：补偿语境消失，全量揭示清欠（消费钳制由 LazyList
            // 边界处理兜底——index==0 时 offset 负值被钳 0，无害）
            reportedHeight = realHeight
            injectedPending = 0
            return Decision(realHeight, 0, false)
        }
        return if (extra > 0) {
            // 有新增长：注入 extra（下一遍遍首消费）、本遍只揭示已消费部分，
            // 未消费的 extra 保持裁剪——未补偿状态永不被放置（渲染前保证）。
            // version++ 使本节点在消费遍被强制重测（配对关键，见 version 注释）。
            injectedPending += extra
            version++
            Decision(revealHeight, extra, true)
        } else {
            // 无新增长（或收缩）：完全揭示，重置基准
            reportedHeight = realHeight
            injectedPending = 0
            Decision(realHeight, 0, false)
        }
    }
}

/**
 * [DeferredRevealCompensator] 的 layout 包装：无界测量取真实高度 → 状态机决策
 * → 上报决策高度（延迟分支裁剪未消费增量）。必须与 clipToBounds 同链使用
 * （clip 在本 layout 外层，裁剪矩形=上报高度）。
 */
internal fun Modifier.deferredRevealCompensation(
    listState: LazyListState,
    compensator: DeferredRevealCompensator,
    shouldCompensate: () -> Boolean,
    logTag: String,
): Modifier = this.layout { measurable, constraints ->
    // 订阅版本号：建立「注入 → 本节点失效 → 消费遍重测本节点」的配对闭环。
    // 读取必须在 measure 块内（快照订阅作用于布局节点）。require 消费读取结果，
    // 防编译器消除。
    require(compensator.version >= 0)
    val placeable = measurable.measure(
        constraints.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
    )
    val realHeight = placeable.height
    // 2026-08-27 #239 竞态修复（当日二审纠偏——首版滚动中走全揭示分支是错的：
    // 揭示增长无配对注入 = 内容被 SSE 增长顶起，用户实测「fling 中随流输出
    // 上推」）。正确形态 = 滚动保持（holdReveal）：滚动中既不注入（scrollToBe
    // Consumed 反射写的残量会撞 fling scrollBy → 契约违规 → v2 防御等帧 = 零
    // 位移顿挫，受控基线 24 次/10 fling）也不全揭示——增长裁剪保持不可见，
    // 视口零位移。
    // 此处**同步**读 isScrollInProgress（快照零滞后——snapshotFlow 驱动的标志
    // 位有 1-2 帧组合滞后）；measure 块内的读取同时建立订阅：滚动结束下降沿
    // 自动失效本节点测量 → 复测一遍立即恢复配对（否则 newest 文本在服务器
    // 14s 爆发间隙里会被裁剪最长一整个间隙）。
    val scrolling = listState.isScrollInProgress
    val decision = compensator.onMeasure(
        realHeight,
        shouldCompensate = shouldCompensate(),
        holdReveal = scrolling,
        shiftApplied = PreRenderShiftChannel.shiftSettled(listState),
    )
    if (decision.injectDelta > 0) {
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.w(
                "ScrollDiag",
                logTag + " defer realH=" + realHeight +
                    " report=" + decision.reportHeight +
                    " inject=" + decision.injectDelta +
                    " pend=" + compensator.injectedPending
            )
        }
        // #258 换道：帧界排队（下一帧 measure 遍首经 request-position 通道应用），
        // 不再反射直写 scrollToBeConsumed——drag 竞态崩溃根因拆除（机制见
        // PreRenderShiftChannel；配对语义不变：本遍只上报已消费基准，下一遍全量揭示）。
        PreRenderShiftChannel.enqueue(listState, decision.injectDelta.toFloat())
    }
    layout(placeable.width, decision.reportHeight) {
        placeable.placeRelative(0, 0)
    }
}

// --- 反射：绕过官方 requestScrollToItem 的 scroll{} 互斥锁取消机制 ---
// 官方 requestScrollToItem（@ExperimentalFoundationApi）做两件事：
//   ① if (isScrollInProgress) scroll {} ← 获取互斥锁，杀死 fling
//   ② scrollPosition.requestPosition + invalidateScope ← 设置待定位置
// 我们只想要 ② —— 设置待定位置而不杀死 fling 惯性（SSE 流式期间的体验关键）。
// 反射直接访问 LazyListState 的 private/internal 字段，绕过 ①。
//
// ⚠️ 反射依赖的私有成员（Compose BOM 2026.05.01，见 app/build.gradle.kts:132）：
//   - 字段 androidx.compose.foundation.lazy.LazyListState.scrollPosition
//   - 方法 scrollPosition.requestPositionAndForgetLastKnownKey(Int, Int)
//   - 字段 androidx.compose.foundation.lazy.LazyListState.measurementScopeInvalidator: MutableState<Unit>
// Compose 版本升级前必须手动测试这些成员仍存在（AGENTS.md「精准修改」规则）。
// 升级后若成员消失/改名 → 初始化探测一次性失败 → 降级为官方 requestScrollToItem
//（取消 fling 但功能等价、不崩溃）。
internal object LazyListReflection {
    // 一次性探测：失败返回 null，后续永久走降级路径。
    private val scrollPositionField: java.lang.reflect.Field? =
        lookupField("androidx.compose.foundation.lazy.LazyListState", "scrollPosition")

    private val requestPositionMethod: java.lang.reflect.Method? =
        scrollPositionField?.type?.let { type ->
            lookupMethod(
                type,
                "requestPositionAndForgetLastKnownKey",
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
            )
        }

    private val invalidatorField: java.lang.reflect.Field? =
        lookupField("androidx.compose.foundation.lazy.LazyListState", "measurementScopeInvalidator")

    // #258 换道手术（2026-08-29）：scrollToBeConsumed 反射直写已整体删除——
    // 用户 drag 起手经 onScroll（LazyListState.kt:492）对该残量有
    // checkPrecondition 断言，直写与用户输入根本竞态（真机 FATAL 实证）。
    // 渲染前补偿改走 PreRenderShiftChannel（帧界排空 + request-position 通道）。

    private fun lookupField(className: String, name: String): java.lang.reflect.Field? = try {
        Class.forName(className).getDeclaredField(name).apply { isAccessible = true }
    } catch (t: Throwable) {
        // NoSuchFieldException / NoSuchFieldError / ClassNotFoundException 等
        AppLogger.w("LazyListReflection", "field $className.$name not found: ${t.message}")
        null
    }

    private fun lookupMethod(
        type: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): java.lang.reflect.Method? = try {
        type.getDeclaredMethod(name, *params).apply { isAccessible = true }
    } catch (t: Throwable) {
        // NoSuchMethodException / NoSuchMethodError 等
        AppLogger.w("LazyListReflection", "method ${type.name}.$name not found: ${t.message}")
        null
    }

    /**
     * 设置待定滚动位置但不取消进行中的 fling（反射路径）。
     * 反射初始化失败或运行时 invoke 抛异常时降级为官方 [LazyListState.requestScrollToItem]
     *（@ExperimentalFoundationApi；会取消 fling，但保证位置设置生效、不崩溃）。
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun requestScrollToItemNoCancel(state: LazyListState, index: Int, scrollOffset: Int) {
        // smart-cast 友好：局部非空变量
        val sp = scrollPositionField
        val rpm = requestPositionMethod
        val inv = invalidatorField
        if (sp != null && rpm != null && inv != null) {
            try {
                val pos = sp.get(state)
                rpm.invoke(pos, index, scrollOffset)
                @Suppress("UNCHECKED_CAST")
                (inv.get(state) as MutableState<Unit>).value = Unit
                return
            } catch (t: Throwable) {
                // IllegalAccessException / IllegalArgumentException / ClassCastException 等
                AppLogger.w("LazyListReflection", "invoke failed, fallback: ${t.message}")
            }
        }
        // 降级：官方 API。语义差异 = 通过 scroll{} 互斥锁取消 fling，可接受。
        state.requestScrollToItem(index, scrollOffset)
    }

    // #258 换道手术（2026-08-29）：requestScrollShift（scrollToBeConsumed 直写）
    // 已整体删除，实现存档 git history。它承载的「渲染前注入」职责由
    // PreRenderShiftChannel 承接：帧界排空 → requestScrollToItemNoCancel。
    // 当年 #222 定因链（测量中途注入被 updateFromMeasureResult 回写覆盖）
    // 在新通道不成立：帧界注入时上一遍已完全结束，无中途覆盖窗口——
    // request-position 通道由此成为无崩溃且无回写竞争的最终形态。
}
