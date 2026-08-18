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
 * 使用方（2026-08-18 全量统一后）：提问卡（活动/历史）、FileCard、全部工具卡
 * （ToolCardScaffold 家族）、TokenUsageCard、ToolProgressCard、CompactionCard
 * 展开态、ReasoningBlock、SyntheticNotificationCard——聊天内中性内容卡片的
 * 唯一容器语言。语义色卡（任务状态蓝/绿/红、错误红）通过 [containerColor]
 * 覆盖底色，形状/边框语言不变。
 */
@Composable
fun EmbeddedCardContainer(
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    /** 底色覆盖（语义状态卡用：任务 发起=蓝/完成=绿/失败=红 等）。
     *  默认 Unspecified → surfaceContainerLow（标准内嵌卡片底）。 */
    containerColor: Color = Color.Unspecified,
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
        color = if (containerColor == Color.Unspecified) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        content()
    }
}
