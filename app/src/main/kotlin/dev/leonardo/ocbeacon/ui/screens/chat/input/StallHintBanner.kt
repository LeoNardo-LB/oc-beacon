package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #562（会话停滞降级提示）：Busy 且长时间无 SSE 事件（120s，见
 * [dev.leonardo.ocbeacon.data.repository.evaluateStalled]）时的输入栏上方
 * 降级横幅——告知用户「服务器长时间无响应，可继续等待或点停止」。
 *
 * 语义边界：仅提示，不改变任何状态机行为（zombie 中断/waiting 等待判定
 * 独立运行）；事件流恢复后 ≤5s 自动消失（stall 派生集出集）。
 * 视觉语言与 [ShellModeHintBanner] 一致（薄横幅+小图标+bodySmall）。
 */
@Composable
internal fun StallHintBanner(
    stalled: Boolean
) {
    AnimatedVisibility(
        visible = stalled,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.XS.dp)
                .clip(ShapeTokens.mediumSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = stringResource(R.string.chat_stall_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
