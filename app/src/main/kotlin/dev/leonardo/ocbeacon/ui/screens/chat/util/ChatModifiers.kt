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
 * 若启用了触觉反馈则执行一次轻微的触觉滴答。
 * 在 composable 上下文中或可访问 View 的点击 lambda 中调用。
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
 * 对代码块有条件地应用 horizontalScroll。
 * 启用自动换行时不应用水平滚动。
 */
@Composable
internal fun Modifier.codeHorizontalScroll(): Modifier {
    return this.fillMaxWidth().horizontalScroll(rememberScrollState())
}


@Composable
internal fun halfScreenHeight(): Dp {
    return maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
}
