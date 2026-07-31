package dev.leonardo.octether.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import dev.leonardo.octether.ui.screens.settings.components.getLanguageDisplayName
import dev.leonardo.octether.ui.screens.settings.components.getReconnectModeDisplayName
import dev.leonardo.octether.ui.theme.ListItemTokens

@Composable
fun GeneralSection(
    viewModel: SettingsViewModel,
    onShowLanguageDialog: () -> Unit,
    onShowReconnectModeDialog: () -> Unit,
) {
    val currentLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val reconnectMode by viewModel.reconnectMode.collectAsStateWithLifecycle()

    SectionHeader(stringResource(R.string.settings_section_general))

    // Language
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_language)) },
        supportingContent = { Text(getLanguageDisplayName(currentLanguage)) },
        leadingContent = {
            Icon(Icons.Default.Language, contentDescription = stringResource(R.string.a11y_settings_language))
        },
        modifier = Modifier.clickable { onShowLanguageDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Reconnect mode
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_reconnect_mode)) },
        supportingContent = { Text(getReconnectModeDisplayName(reconnectMode)) },
        leadingContent = {
            Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.a11y_settings_reconnect_mode))
        },
        modifier = Modifier.clickable { onShowReconnectModeDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
