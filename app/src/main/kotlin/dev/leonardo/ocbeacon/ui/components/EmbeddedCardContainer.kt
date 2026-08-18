package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * 聊天气泡内嵌卡片的统一基础容器（2026-08-18 用户提出：提问卡应与其他卡片
 * 共用基础容器——此前提问卡 tonal 实底（surfaceContainerHighest）与气泡
 * （surfaceContainerHigh）仅差一档且无边框，视觉上"融进"气泡不像独立卡片）。
 *
 * 样式 = FileCard 既有语言（本应用内嵌卡片的既有基准）：
 * - 实底 surfaceContainerLow——比气泡亮一档，读作"内嵌卡片"而非"挖洞"
 * - 1dp outlineVariant 细边框（AMOLED @HIGH，普通 @AMOLED）——双主题下
 *   都有清晰容器边界
 * - 圆角 ShapeTokens.medium(12dp)——与 assistant 气泡同族
 * - tonalElevation 0——层级由色彩差承担，不加投影
 *
 * 使用方：提问卡（活动 QuestionCard / 历史 CollapsibleQuestionPart）、FileCard。
 */
@Composable
fun EmbeddedCardContainer(
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val borderColor = if (isAmoled) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.AMOLED)
    }
    Surface(
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        content()
    }
}
