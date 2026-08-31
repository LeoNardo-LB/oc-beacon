package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 会话运行错误转录内行（D1③，走查 #2 对齐 DSH turn-error 语义）：
 * 渲染在聊天消息流（ChatMessageList LazyColumn）内、随历史滚动，非悬浮/常驻
 * 浮层；无 dismiss 按钮（DSH TurnErrorItem 即 transcript 内 status 行，无
 * 手动关闭），sendMessage 成功自动清空（[dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler.clearSessionErrors]）。
 */
@Composable
internal fun SessionErrorCard(
    error: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
