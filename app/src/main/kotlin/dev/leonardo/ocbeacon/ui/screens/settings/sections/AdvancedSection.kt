package dev.leonardo.ocbeacon.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.ListItemTokens

@Composable
fun AdvancedSection(
    viewModel: SettingsViewModel,
    onNavigateToDiagnostics: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_section_advanced))

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
