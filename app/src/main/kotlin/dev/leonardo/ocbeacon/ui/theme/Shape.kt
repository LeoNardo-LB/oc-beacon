package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 标准 Material3 形状刻度 + 用于精细控制的自定义中间令牌。
 *
 * Material3 [Shapes]，被 [androidx.compose.material3.MaterialTheme] 使用：
 *   extraSmall=4, small=8, medium=12, large=16, extraLarge=28
 *
 * 扩展令牌（不属于 Material3）：
 *   none=0, smallMedium=6, mediumSmall=10, largeMedium=20
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** AMOLED 变体 — 更小的圆角，呈现更锐利、极简的外观。 */
val AmoledShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

// 扩展令牌 — 当 Material3 的 5 级刻度不足时直接使用。
object ShapeTokens {
    val none = RoundedCornerShape(0.dp)
    val extraSmall = RoundedCornerShape(4.dp)
    val smallMedium = RoundedCornerShape(6.dp)
    val small = RoundedCornerShape(8.dp)
    val mediumSmall = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val largeMedium = RoundedCornerShape(20.dp)
    val extraLarge = RoundedCornerShape(28.dp)
}
