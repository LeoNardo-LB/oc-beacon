package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import androidx.compose.ui.graphics.Color

/**
 * #309 批1⑤：输出达上限通知卡（Web turn-max-tokens 通知节点对位）。
 *
 * - 触发：DSH turn/end reason.kind="max-tokens"（本轮被 provider 截断）；
 * - 续写：再发一条 "continue" prompt（wire 无专用 continue 端点——Web 同款
 *   语义，续写词固定英文原词，不本地化）；
 * - 退场：新一轮 turn/start（SessionStatus Busy）dispatcher 跨 handler 自动清。
 */
@Composable
internal fun TurnMaxTokensCard(
    onContinue: () -> Unit,
) {
    Surface(
        // 2026-09-20 单行形态(Q2 ok):达上限卡去底色
        color = Color.Transparent,
        shape = ShapeTokens.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.turn_max_tokens_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.size(SpacingTokens.SM.dp))
            Button(onClick = onContinue) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.size(SpacingTokens.XS.dp))
                Text(stringResource(R.string.turn_max_tokens_continue))
            }
        }
    }
}
