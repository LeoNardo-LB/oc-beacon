package dev.leonardo.octether.ui.screens.sessions.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.domain.model.McpServerStatus
import dev.leonardo.octether.ui.theme.StatusConnected
import dev.leonardo.octether.ui.theme.StatusFailed
import dev.leonardo.octether.ui.theme.StatusWarning

@Composable
fun McpServerRow(
    server: McpServerStatus,
    isLoading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = buildString {
                    append(server.type)
                    append(" \u00b7 ")
                    append(statusDot(server.status))
                    append(" ")
                    append(server.status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(server.status)
            )
        }
        Switch(
            checked = server.status == "connected",
            onCheckedChange = { onToggle() },
            enabled = !isLoading
                    && server.status != "needs_auth"
                    && server.status != "needs_client_registration"
        )
    }
}

private fun statusDot(status: String): String = when (status) {
    "connected" -> "\u25cf"
    "disabled" -> "\u25cb"
    "failed" -> "\u25cf"
    "needs_auth" -> "\u25cf"
    "needs_client_registration" -> "\u25cf"
    else -> "\u25cb"
}

private fun statusColor(status: String): Color = when (status) {
    "connected" -> StatusConnected
    "disabled" -> Color.Gray
    "failed" -> StatusFailed
    "needs_auth", "needs_client_registration" -> StatusWarning
    else -> Color.Gray
}
