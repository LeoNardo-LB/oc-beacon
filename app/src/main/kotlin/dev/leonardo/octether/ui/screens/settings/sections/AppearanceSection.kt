package dev.leonardo.octether.ui.screens.settings.sections

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
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
import dev.leonardo.octether.ui.screens.settings.components.getThemeDisplayName
import dev.leonardo.octether.ui.theme.ListItemTokens

@Composable
fun AppearanceSection(
    viewModel: SettingsViewModel,
    onShowThemeDialog: () -> Unit,
) {
    val currentTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val amoledDark by viewModel.amoledDark.collectAsStateWithLifecycle()
    val switchColors = SwitchDefaults.colors()

    SectionHeader(stringResource(R.string.settings_section_appearance))

    // Theme
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_theme)) },
        supportingContent = { Text(getThemeDisplayName(currentTheme)) },
        leadingContent = {
            Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.a11y_settings_theme))
        },
        modifier = Modifier.clickable { onShowThemeDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Dynamic colors (only on Android 12+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
            supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
            leadingContent = {
                Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.a11y_settings_dynamic_color))
            },
            trailingContent = {
                Switch(
                    checked = dynamicColor,
                    onCheckedChange = { viewModel.setDynamicColor(it) },
                    colors = switchColors
                )
            },
            modifier = Modifier.clickable { viewModel.setDynamicColor(!dynamicColor) }.padding(ListItemTokens.ContentPaddingMedium)
        )
    }

    // AMOLED dark mode
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_amoled_dark)) },
        supportingContent = { Text(stringResource(R.string.settings_amoled_dark_desc)) },
        leadingContent = {
            Icon(Icons.Default.DarkMode, contentDescription = stringResource(R.string.a11y_settings_amoled_dark))
        },
        trailingContent = {
            Switch(
                checked = amoledDark,
                onCheckedChange = { viewModel.setAmoledDark(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setAmoledDark(!amoledDark) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
