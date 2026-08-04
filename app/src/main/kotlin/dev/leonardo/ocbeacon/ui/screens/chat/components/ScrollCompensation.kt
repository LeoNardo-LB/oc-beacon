package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState

/**
 * SSE 流式期间高度补偿的可变状态。
 * 跟踪上次测量的高度以及是否应应用补偿。
 */
internal class CompensateState {
    var lastHeight: Int = 0
    var shouldCompensate: Boolean = false
}

// --- 反射：绕过 requestScrollToItem 的 scroll{} 互斥锁取消机制 ---
// requestScrollToItem 做两件事：
//   ① if (isScrollInProgress) scroll {} ← 获取互斥锁，杀死 fling
//   ② scrollPosition.requestPosition + invalidateScope ← 设置待定位置
// 我们只想要 ② —— 设置待定位置而不杀死 fling 惯性。
// 反射直接访问 private/internal 字段。
internal object LazyListReflection {
    private val scrollPositionField by lazy {
        Class.forName("androidx.compose.foundation.lazy.LazyListState")
            .getDeclaredField("scrollPosition")
            .apply { isAccessible = true }
    }

    private val requestPositionMethod by lazy {
        scrollPositionField.type
            .getDeclaredMethod("requestPositionAndForgetLastKnownKey",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    private val invalidatorField by lazy {
        Class.forName("androidx.compose.foundation.lazy.LazyListState")
            .getDeclaredField("measurementScopeInvalidator")
            .apply { isAccessible = true }
    }

    fun requestScrollToItemNoCancel(state: LazyListState, index: Int, scrollOffset: Int) {
        val scrollPosition = scrollPositionField.get(state)
        requestPositionMethod.invoke(scrollPosition, index, scrollOffset)
        @Suppress("UNCHECKED_CAST")
        (invalidatorField.get(state) as MutableState<Unit>).value = Unit
    }
}
