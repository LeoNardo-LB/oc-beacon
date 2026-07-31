package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState

/**
 * Mutable state for height-compensation during SSE streaming.
 * Tracks the last measured height and whether compensation should be applied.
 */
internal class CompensateState {
    var lastHeight: Int = 0
    var shouldCompensate: Boolean = false
}

// --- Reflection: bypass requestScrollToItem's scroll{} mutex cancellation ---
// requestScrollToItem does two things:
//   ① if (isScrollInProgress) scroll {} ← grabs mutex, KILLS fling
//   ② scrollPosition.requestPosition + invalidateScope ← sets pending position
// We only want ② — set pending position without killing fling inertia.
// Reflection accesses private/internal fields directly.
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
