package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.MessageStatusBadge
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters

/**
 * （2026-09-12 消息层扁平化）L1 消息层三段式骨架——**无背景 / 无边框 / 无圆角**。
 *
 * 头部标题栏 / 正文栏 / 尾部统计栏（US#1-16）。与通知层的 [EventCard]（沿用
 * 现役 MessageBubble 容器：透明底 + 1dp 描边 + medium 圆角）形成两层语言：
 * **消息 = 内容，平面；事件 = 通知，成卡**。
 *
 * - 头部：左 = 角色图标 + 角色标签 + agent 名（有则）；右 = 状态徽标 + 时间戳。
 * - 正文：调用方内容（工具卡 / 推理块保持各自容器——卡片层是 #215 的范围）。
 * - 尾部：模型名 + provider 图标 / 步数·工具数摘要 / 产出文件行 / 常显动作 +
 *   「更多」——由调用方以 [tail] 供槽位。
 * - 用户消息：右对齐 + 最大宽度 82%（[alignEnd] + [maxWidthFraction]）。
 * - 间距与分隔：仅靠消息间距（ChatScreen）+ 头部角色标签分隔，不加分隔线；
 *   AMOLED 下不再有气泡描边（接受，US#42 由头部标签承担结构可读性）。
 *
 * [MessageBubble] 的 flat 模式委派到本实现——扁平三段式只有这一份布局代码。
 *
 * @param labelLeading 头部前导图标（角色图标）
 * @param showHeader 分片 / 分段渲染时仅首段渲染头部
 * @param showTail 仅末段渲染尾部
 */
@Composable
internal fun MessageSectionScaffold(
    label: String,
    timeMs: Long,
    modifier: Modifier = Modifier,
    labelLeading: (@Composable () -> Unit)? = null,
    alignEnd: Boolean = false,
    maxWidthFraction: Float? = null,
    agentName: String? = null,
    agents: List<AgentInfo> = emptyList(),
    onAgentClick: ((String) -> Unit)? = null,
    statusBadge: MessageStatusBadge? = null,
    showHeader: Boolean = true,
    showTail: Boolean = true,
    /** 头部右侧附加内容（时间戳之后的动作位）。 */
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    /** 尾部统计栏（末尾段）。 */
    tail: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalChatDensity.current == ChatDensity.Compact
    val sectionGap = if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Column(
            modifier = if (maxWidthFraction != null) {
                Modifier.fillMaxWidth(maxWidthFraction)
            } else {
                Modifier.fillMaxWidth()
            },
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            // ① 头部标题栏
            if (showHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                ) {
                    labelLeading?.invoke()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!agentName.isNullOrBlank()) {
                        AgentTag(
                            agent = agentName,
                            tagColor = agentColor(agentName, agents),
                            onClick = onAgentClick?.let { cb -> { cb(agentName) } },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (statusBadge != null) {
                        MessageStatusBadgeLabel(statusBadge)
                    }
                    Text(
                        text = remember(timeMs) { DateFormatters.messageTimestamp(timeMs) },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                    headerTrailing?.invoke(this)
                }
            }

            // ② 正文栏
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sectionGap),
            ) {
                content()
            }

            // ③ 尾部统计栏
            if (showTail && tail != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                ) {
                    tail()
                }
            }
        }
    }
}

/**
 * 状态徽标（US#5/#6）：只覆盖进行中 / 已中断 / 出错，完成态不渲染。
 * 形态 = 小号圆角弱底标签（flat，不喧宾夺主）。
 */
@Composable
internal fun MessageStatusBadgeLabel(badge: MessageStatusBadge) {
    val tint: Color = when (badge) {
        MessageStatusBadge.STREAMING -> MaterialTheme.colorScheme.primary
        MessageStatusBadge.INTERRUPTED -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageStatusBadge.ERROR -> MaterialTheme.colorScheme.error
    }
    val text = when (badge) {
        MessageStatusBadge.STREAMING -> stringResource(R.string.chat_msg_status_streaming)
        MessageStatusBadge.INTERRUPTED -> stringResource(R.string.chat_msg_status_interrupted)
        MessageStatusBadge.ERROR -> stringResource(R.string.chat_msg_status_error)
    }
    Surface(
        color = tint.copy(alpha = AlphaTokens.FAINT),
        contentColor = tint,
        shape = RoundedCornerShape(SpacingTokens.XS.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = SpacingTokens.XS.dp, vertical = 1.dp),
        )
    }
}
