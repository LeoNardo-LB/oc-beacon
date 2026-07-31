package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut

object AppMotion {
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 500
    const val BREATH_CYCLE = 800    // Breathing indicator cycle
    const val PULSE_CYCLE = 1200    // Pulsing dots full cycle
    const val TERMINAL = 700        // Terminal transition

    val StandardEasing = EaseInOut
    val EmphasizedEasing = EaseOut
    val ExitEasing = EaseIn
}
