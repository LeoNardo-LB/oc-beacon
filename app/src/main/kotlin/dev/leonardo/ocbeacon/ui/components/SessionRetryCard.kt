package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.delay

/**
 * 在聊天会话内显示重试状态的紧凑卡片。
 *
 * 显示带尝试次数文本的 [CircularProgressIndicator]、表示尝试进度的
 * [LinearProgressIndicator]、可选的倒计时器，以及可选的错误消息
 * （截断到 80 个字符）。
 *
 * @param attempt         当前重试次数（从 1 开始）。
 * @param maxAttempts     最大重试次数（默认 3）。
 * @param countdownSeconds 距下次重试的秒数；为 null 时隐藏倒计时。
 * @param errorMessage    可选的错误描述（截断到 80 个字符）。
 * @param modifier        根 Card 的 Modifier。
 */
@Composable
fun SessionRetryCard(
    attempt: Int,
    maxAttempts: Int = 3,
    countdownSeconds: Int?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    var remainingSeconds by remember(countdownSeconds) {
        mutableIntStateOf(countdownSeconds ?: 0)
    }

    // 倒计时循环：每秒递减，直到为 0
    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
    }

    // 将错误消息截断到 80 个字符
    val displayError = errorMessage?.let {
        if (it.length > 80) it.take(80) + "…" else it
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 顶行：spinner + 尝试次数标签 + 倒计时 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )

                Text(
                    text = stringResource(R.string.session_retry_attempt, attempt, maxAttempts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )

                if (remainingSeconds > 0) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${remainingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(
                            alpha = AlphaTokens.MUTED,
                        ),
                    )
                }
            }

            // ── 尝试进度条 ──
            LinearProgressIndicator(
                progress = {
                    if (maxAttempts > 0) attempt.toFloat() / maxAttempts.toFloat() else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onErrorContainer,
                trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(
                    alpha = AlphaTokens.FAINT,
                ),
            )

            // ── 错误消息 ──
            if (displayError != null) {
                Text(
                    text = displayError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(
                        alpha = AlphaTokens.MEDIUM,
                    ),
                    maxLines = 2,
                )
            }
        }
    }
}
