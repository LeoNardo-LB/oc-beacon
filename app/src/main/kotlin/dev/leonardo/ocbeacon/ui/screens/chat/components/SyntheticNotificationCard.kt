package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统通知卡片（#67 synthetic 消息）。
 *
 * opencode v2 后台任务/subagent 完成时向主会话注入 synthetic 消息
 * （REST GET /message 的 type="synthetic" + 顶层 text；无 SSE 事件）。
 * 以"系统通知"样式渲染，与普通用户气泡区分：
 * 居中淡色圆角条 + Info 图标 + 文本 + 时间。
 */
@Composable
internal fun SyntheticNotificationCard(
    currentMessage: ChatMessage,
    isAmoled: Boolean = false,
) {
    val text = currentMessage.parts
        .filterIsInstance<Part.Text>()
        .firstOrNull { it.text.isNotBlank() }
        ?.text
        ?: (currentMessage.message as? Message.User)?.summary?.body
        ?: return

    val timeText = remember(currentMessage.message.time.created) {
        SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(currentMessage.message.time.created))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isAmoled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.MUTED)
            },
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
                Spacer(modifier = Modifier.width(SpacingTokens.SM.dp))
                Column {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                    )
                }
            }
        }
    }
}
