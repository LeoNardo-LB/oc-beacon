package dev.leonardo.ocbeacon.ui.screens.chat.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Perform a light haptic tick if haptic feedback is enabled.
 * Call from composable context or from a click lambda that has access to a View.
 */
@Suppress("DEPRECATION")
internal fun performHaptic(view: View, enabled: Boolean) {
    if (enabled) {
        view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}

/**
 * Conditionally applies horizontalScroll for code blocks.
 * When word wrap is enabled, no horizontal scroll is applied.
 */
@Composable
internal fun Modifier.codeHorizontalScroll(): Modifier {
    return this.fillMaxWidth().horizontalScroll(rememberScrollState())
}


@Composable
internal fun halfScreenHeight(): Dp {
    return maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
}
