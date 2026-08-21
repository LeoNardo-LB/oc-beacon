package dev.leonardo.ocbeacon.ui.screens.settings.sections

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.platform.LocalContext
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
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.ListItemTokens

@Composable
fun NotificationsSection(viewModel: SettingsViewModel) {
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val silentNotifications by viewModel.silentNotifications.collectAsStateWithLifecycle()
    val switchColors = SwitchDefaults.colors()
    val context = LocalContext.current

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

    // 静默通知
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

    // 通知自检（验收①根因收尾）：厂商（MIUI 等）对旁装载应用默认关悬浮通知且标准 API 查不到，
    // 唯一可靠自检 = 真实投递 + 用户感知确认
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_notif_test)) },
        supportingContent = { Text(stringResource(R.string.settings_notif_test_desc)) },
        leadingContent = {
            Icon(Icons.Default.NotificationsActive, contentDescription = stringResource(R.string.a11y_settings_notif_test))
        },
        modifier = Modifier.clickable { viewModel.sendTestNotification(context.applicationContext) }
            .padding(ListItemTokens.ContentPaddingMedium)
    )

    // 直达系统通知设置（渠道级 悬浮/声音/振动 在此处开启）
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_notif_system)) },
        supportingContent = { Text(stringResource(R.string.settings_notif_system_desc)) },
        leadingContent = {
            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.a11y_settings_notif_system))
        },
        modifier = Modifier.clickable {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        }.padding(ListItemTokens.ContentPaddingMedium)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
