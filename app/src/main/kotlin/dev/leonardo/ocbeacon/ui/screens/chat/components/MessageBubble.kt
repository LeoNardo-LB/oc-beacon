package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * 统一消息气泡容器（2026-08-12 用户要求：标签栏/正文栏/统计栏样式强一致）。
 *
 * 三种角色（用户 / 智能体 / 合成通知）共用同一外层结构，仅通过参数区分：
 * - [alignEnd]：user 右对齐（true）；assistant/synthetic 左对齐（false）
 * - [containerColor] / [border]：底色与边框（synthetic = 透明 + 边框类型）
 * - [shape]：圆角（user 用聊天气泡非对称圆角；其他用 medium）
 * - 标签栏统一：`[时间] [labelLeading?] [类型标签] [Spacer] [labelTrailing?]`
 * - 统计栏可选（assistant 的 agent/模型/时长/复制；user 的 QUEUED 徽章）
 */
@Composable
internal fun MessageBubble(
    alignEnd: Boolean,
    containerColor: Color,
    label: String,
    timeMs: Long,
    modifier: Modifier = Modifier,
    shape: Shape = ShapeTokens.medium,
    border: BorderStroke? = null,
    labelLeading: (@Composable () -> Unit)? = null,
    /** label 之后的附加内容（如状态文案 + 状态图标——合成通知用）。 */
    labelSuffix: (@Composable () -> Unit)? = null,
    labelTrailing: (@Composable RowScope.() -> Unit)? = null,
    statsBar: (@Composable RowScope.() -> Unit)? = null,
    /** 卡片级点击（#234 事件卡展开/收起用）；null 时不可点（用户/智能体气泡不受影响）。 */
    onCardClick: (() -> Unit)? = null,
    /** 标签行水平内边距（#234 V6 反馈：事件卡标题行右贴边）；null=沿用内容内边距
     *  （用户/智能体气泡默认路径，渲染几何不变）。 */
    labelRowHorizontalPadding: androidx.compose.ui.unit.Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalChatDensity.current == ChatDensity.Compact

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        // 2026-08-12 M3 优化：Surface → M3 Card（Filled/Outlined 通用——shape/
        // colors/border/elevation 全参数化，支持气泡样式；阴影设 0 保持气泡观感）
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .clickable(enabled = onCardClick != null) { onCardClick?.invoke() }
                    .padding(vertical = if (compact) SpacingTokens.SM.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) SpacingTokens.XS.dp else 10.dp)
            ) {
                // 水平缩进下沉到节级（原为 Column 级整段 padding）——渲染几何等价；
                // 拆开的目的是让标签行可独立收窄内边距（标题行贴边，#234 V6 反馈）。
                val contentHPad = if (compact) 10.dp else SpacingTokens.LG.dp
                // ① 标签栏（统一）：[时间] [前导图标?] [类型标签] [Spacer] [右侧操作]
                // 2026-08-16（标题栏规范）：条件时间戳——当天 HH:mm:ss，
                // 非当天 yyyy-MM-dd HH:mm:ss（DateFormatters.messageTimestamp）
                val timeText = remember(timeMs) {
                    DateFormatters.messageTimestamp(timeMs)
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = labelRowHorizontalPadding ?: contentHPad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                    )
                    labelLeading?.invoke()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    labelSuffix?.invoke()
                    Spacer(modifier = Modifier.weight(1f))
                    labelTrailing?.invoke(this)
                }

                // ② 正文栏（水平缩进在节级；内层 spacedBy 复刻原 Column 级间距）
                Column(
                    modifier = Modifier.padding(horizontal = contentHPad),
                    verticalArrangement = Arrangement.spacedBy(if (compact) SpacingTokens.XS.dp else 10.dp)
                ) {
                    content()
                }

                // ③ 统计栏（可选）
                if (statsBar != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = contentHPad)
                            .padding(top = if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        statsBar(this)
                    }
                }
            }
        }
    }
}

/** 统一气泡圆角（user 聊天气泡非对称样式）。 */
internal val UserBubbleShape: Shape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 4.dp,
    bottomStart = 18.dp,
    bottomEnd = 18.dp
)
