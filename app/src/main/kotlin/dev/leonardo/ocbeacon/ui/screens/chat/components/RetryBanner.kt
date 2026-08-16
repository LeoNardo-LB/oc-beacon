package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
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
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

@Composable
internal fun RetryBanner(retry: SessionStatus.Retry) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
        shape = ShapeTokens.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.XS.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // #120（D2-09）：单占位符——原双占位符传同一值恒显示 N/N
                    text = stringResource(R.string.session_status_retry, retry.attempt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (retry.message.isNotBlank()) {
                    Text(
                        text = retry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AlphaTokens.HIGH)
                    )
                }
            }
        }
    }
}
