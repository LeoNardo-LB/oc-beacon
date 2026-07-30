package dev.leonardo.ocremoteplus.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
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
import dev.leonardo.ocremoteplus.R
import dev.leonardo.ocremoteplus.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocremoteplus.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocremoteplus.ui.theme.ListItemTokens

@Composable
fun AdvancedSection(
    viewModel: SettingsViewModel,
    onShowLocalLaunchOptionsDialog: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
) {
    val showLocalRuntime by viewModel.showLocalRuntime.collectAsStateWithLifecycle()
    val switchColors = SwitchDefaults.colors()

    SectionHeader(stringResource(R.string.settings_section_advanced))

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_local_runtime)) },
        supportingContent = { Text(stringResource(R.string.settings_local_runtime_desc)) },
        leadingContent = {
            Icon(Icons.Default.Code, contentDescription = stringResource(R.string.a11y_settings_local_runtime))
        },
        trailingContent = {
            Switch(
                checked = showLocalRuntime,
                onCheckedChange = { viewModel.setShowLocalRuntime(it) },
                colors = switchColors,
            )
        },
        modifier = Modifier.clickable { viewModel.setShowLocalRuntime(!showLocalRuntime) }.padding(ListItemTokens.ContentPaddingMedium),
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.home_local_launch_options)) },
        supportingContent = { Text(stringResource(R.string.home_local_launch_options_desc)) },
        leadingContent = {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.a11y_settings_launch_options))
        },
        modifier = Modifier.clickable { onShowLocalLaunchOptionsDialog() }.padding(ListItemTokens.ContentPaddingMedium),
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_diagnostics)) },
        supportingContent = { Text(stringResource(R.string.settings_diagnostics_desc)) },
        leadingContent = {
            Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.settings_diagnostics))
        },
        modifier = Modifier.clickable { onNavigateToDiagnostics() }.padding(ListItemTokens.ContentPaddingMedium),
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
