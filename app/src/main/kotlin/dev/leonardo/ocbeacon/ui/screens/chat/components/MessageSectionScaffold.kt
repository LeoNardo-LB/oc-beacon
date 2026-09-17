package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.MessageStatusBadge
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * （2026-09-12 消息层扁平化；2026-09-17 v2 两段式）L1 角色消息骨架——
 * **无背景 / 无边框 / 无圆角**，且 **v2 起没有头部标签栏**。
 *
 * 头部（角色图标 / 「用户」「智能体」文字 / agent 标签 / 时间 / 状态徽标）整体删除；
 * 骨架只剩正文栏 + 尾部统计栏。时间 / agent / 状态徽标 / 低频动作由尾部
 * 「详情」入口打开的消息详情弹窗承载。
 *
 * - 正文：调用方内容（工具卡 / 推理块保持各自容器——卡片层是 #215 的范围）。
 * - 尾部：状态徽标 + agent 标签 + 模型 / 步数·工具摘要 / 产出文件行 + 常显动作，
 *   由调用方以 [tail] 供槽位。
 * - 用户消息：右对齐气泡走 [MessageBubble] 非 flat 路径（flat 只服务助手正文）。
 * - 间距与分隔：仅靠消息间距，不加分隔线。
 *
 * @param showTail 分片 / 分段渲染时仅末段渲染尾部
 */
@Composable
internal fun MessageSectionScaffold(
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    showTail: Boolean = true,
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
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            // ① 正文栏
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sectionGap),
            ) {
                content()
            }

            // ② 尾部统计栏
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
 * v2 起挂在尾部统计栏信息簇首位（原头部右侧）。
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
