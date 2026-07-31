package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Standard Material3 shape scale + custom intermediate tokens for fine-grained control.
 *
 * Material3 [Shapes] used by [androidx.compose.material3.MaterialTheme]:
 *   extraSmall=4, small=8, medium=12, large=16, extraLarge=28
 *
 * Extended tokens (not part of Material3):
 *   none=0, smallMedium=6, mediumSmall=10, largeMedium=20
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** AMOLED variant — smaller radii for a sharper, more minimal look. */
val AmoledShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

// Extended tokens — use directly where Material3's 5-level scale is insufficient.
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
