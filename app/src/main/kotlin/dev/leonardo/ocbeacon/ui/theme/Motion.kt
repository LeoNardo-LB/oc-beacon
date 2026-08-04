package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut

object AppMotion {
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 500
    const val BREATH_CYCLE = 800    // 呼吸指示器周期
    const val PULSE_CYCLE = 1200    // 脉冲点完整周期
    const val TERMINAL = 700        // 终端转场时长

    val StandardEasing = EaseInOut
    val EmphasizedEasing = EaseOut
    val ExitEasing = EaseIn
}
