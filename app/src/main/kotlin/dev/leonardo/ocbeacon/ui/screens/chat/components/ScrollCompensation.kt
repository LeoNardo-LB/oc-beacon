package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * SSE 流式期间高度补偿的可变状态。
 * 跟踪上次测量的高度以及是否应应用补偿。
 */
internal class CompensateState {
    var lastHeight: Int = 0
    var shouldCompensate: Boolean = false
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

    // #215 验收反馈·一：scrollToBeConsumed（internal var Float）——滚动消费通道。
    // 用户真实滚动（scrollBy/fling）的必经路径：测量开始时无条件消费
    //（currentFirstItemScrollOffset -= scrollDelta），跨 item 折算与边界钳制由
    // 测量标准流程原生处理，消费结果随测量回写——不存在「请求被回写丢弃」的竞争
    //（toggle 动画期间逐帧修正为什么必须走这条通道的定因，见 journal §验收反馈·一）。
    private val scrollToBeConsumedField: java.lang.reflect.Field? =
        lookupField("androidx.compose.foundation.lazy.LazyListState", "scrollToBeConsumed")

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

    // #215 验收反馈·一（终版裁决）：requestScrollShift 已随方案三整体撤销，
    // 实现存档 git history（a4eedab6）供未来复用。

    /**
     * #222 伴随定案（2026-08-25 通道回写竞争修复）：流式高度补偿注入改走
     * scrollToBeConsumed 消费通道（a4eedab6 封存实现复活）。
     *
     * 定因链：request-position 通道（requestPositionAndForgetLastKnownKey + poke）
     * 在测量**中途**注入，而 LazyListMeasure.kt:423 把本遍**起始** off 原样写回
     * （updateFromMeasureResult）——poke 引发的再测遍若先于回写起跑则注入存活，
     * 否则被覆盖丢弃。真机实测（2026-08-25 分布式长文流式 + 滚离底部）：off 轨迹
     * 785→933→933→1093→1163，fire2 注入（933+72）被吃——间歇性丢失使阅读历史
     * 期视口缓慢上爬。#215 journal 早已实证动画场景同竞争（请求落空、终态跳回）。
     *
     * scrollToBeConsumed 是用户真实滚动（scrollBy/fling）的必经通道：测量开始时
     * **无条件消费**（currentFirstItemScrollOffset -= scrollDelta，LazyListMeasure.kt:142），
     * 消费结果随回写生效——结构性无竞争。注入语义：内容生长 delta 即等量下移
     * （cur - shiftDownPx → 消费时 off += delta），与 request-position 注入等价。
     *
     * 仍是测量期（渲染前）反射注入——遍首消费先于放置，符合用户硬约束。
     * 反射字段不可用时降级回 request-position 通道（有竞争但功能等价）。
     */
    fun requestScrollShift(state: LazyListState, shiftDownPx: Float) {
        val f = scrollToBeConsumedField
        if (f != null) {
            try {
                val cur = f.getFloat(state)
                f.setFloat(state, cur - shiftDownPx)
                invalidatorField?.get(state)?.let {
                    @Suppress("UNCHECKED_CAST")
                    (it as MutableState<Unit>).value = Unit
                }
                return
            } catch (t: Throwable) {
                AppLogger.w("LazyListReflection", "requestScrollShift failed, fallback: ${t.message}")
            }
        }
        // 降级：request-position 通道（存在回写竞争，但保证注入发生）
        requestScrollToItemNoCancel(
            state,
            state.firstVisibleItemIndex,
            state.firstVisibleItemScrollOffset + shiftDownPx.toInt()
        )
    }
}
