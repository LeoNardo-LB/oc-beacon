package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.ui.theme.StatusConnected
import dev.leonardo.ocbeacon.ui.theme.StatusFailed
import dev.leonardo.ocbeacon.ui.theme.StatusWarning

@Composable
fun McpServerRow(
    server: McpServerStatus,
    isLoading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsListRow(
        modifier = modifier,
        leading = {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = server.name,
        subtitle = buildString {
            append(server.type)
            append(" \u00b7 ")
            append(statusDot(server.status))
            append(" ")
            append(server.status)
        },
        subtitleColor = statusColor(server.status),
        trailing = {
            Switch(
                checked = server.status == "connected",
                onCheckedChange = { onToggle() },
                enabled = !isLoading
                        && server.status != "needs_auth"
                        && server.status != "needs_client_registration",
            )
        },
    )
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
