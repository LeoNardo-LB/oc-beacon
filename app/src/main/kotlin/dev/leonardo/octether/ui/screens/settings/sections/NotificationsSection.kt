package dev.leonardo.octether.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.screens.settings.SettingsViewModel
import dev.leonardo.octether.ui.screens.settings.components.SectionHeader
import dev.leonardo.octether.ui.theme.ListItemTokens

@Composable
fun NotificationsSection(viewModel: SettingsViewModel) {
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val silentNotifications by viewModel.silentNotifications.collectAsStateWithLifecycle()
    val switchColors = SwitchDefaults.colors()

    SectionHeader(stringResource(R.string.settings_section_notifications))

    // Notifications
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_notifications)) },
        supportingContent = { Text(stringResource(R.string.settings_notifications_desc)) },
        leadingContent = {
            Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.a11y_settings_notifications))
        },
        trailingContent = {
            Switch(
                checked = notificationsEnabled,
                onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setNotificationsEnabled(!notificationsEnabled) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Silent notifications
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_silent_notifications)) },
        supportingContent = { Text(stringResource(R.string.settings_silent_notifications_desc)) },
        leadingContent = {
            Icon(Icons.Default.NotificationsOff, contentDescription = stringResource(R.string.a11y_settings_silent_notifications))
        },
        trailingContent = {
            Switch(
                checked = silentNotifications,
                onCheckedChange = { viewModel.setSilentNotifications(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setSilentNotifications(!silentNotifications) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
