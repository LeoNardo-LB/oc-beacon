package dev.leonardo.octether.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WrapText
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
fun ChatDisplaySection(
    viewModel: SettingsViewModel,
    onShowChatDensityPicker: () -> Unit,
) {
    val chatDensity by viewModel.chatDensity.collectAsStateWithLifecycle()
    val codeWordWrap by viewModel.codeWordWrap.collectAsStateWithLifecycle()
    val collapseTools by viewModel.collapseTools.collectAsStateWithLifecycle()
    val expandReasoning by viewModel.expandReasoning.collectAsStateWithLifecycle()
    val showTurnDividers by viewModel.showTurnDividers.collectAsStateWithLifecycle()
    val switchColors = SwitchDefaults.colors()

    SectionHeader(stringResource(R.string.settings_section_chat_display))

    // Chat font (density: normal / compact)
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_chat_font)) },
        supportingContent = {
            Text(
                if (chatDensity == "compact") stringResource(R.string.settings_chat_font_compact)
                else stringResource(R.string.settings_chat_font_normal)
            )
        },
        leadingContent = {
            Icon(Icons.Default.FormatSize, contentDescription = stringResource(R.string.settings_chat_font))
        },
        modifier = Modifier.clickable { onShowChatDensityPicker() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Code word wrap
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_code_word_wrap)) },
        supportingContent = { Text(stringResource(R.string.settings_code_word_wrap_desc)) },
        leadingContent = {
            Icon(Icons.Default.WrapText, contentDescription = stringResource(R.string.a11y_settings_code_word_wrap))
        },
        trailingContent = {
            Switch(
                checked = codeWordWrap,
                onCheckedChange = { viewModel.setCodeWordWrap(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setCodeWordWrap(!codeWordWrap) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Auto-expand tool results
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_auto_expand_tools)) },
        supportingContent = { Text(stringResource(R.string.settings_auto_expand_tools_desc)) },
        leadingContent = {
            Icon(Icons.Default.UnfoldMore, contentDescription = stringResource(R.string.a11y_settings_auto_expand_tools))
        },
        trailingContent = {
            Switch(
                checked = collapseTools,
                onCheckedChange = { viewModel.setCollapseTools(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setCollapseTools(!collapseTools) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Expand reasoning by default
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_expand_reasoning)) },
        supportingContent = { Text(stringResource(R.string.settings_expand_reasoning_desc)) },
        leadingContent = {
            Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.a11y_settings_expand_reasoning))
        },
        trailingContent = {
            Switch(
                checked = expandReasoning,
                onCheckedChange = { viewModel.setExpandReasoning(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setExpandReasoning(!expandReasoning) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Show turn dividers
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_turn_dividers)) },
        supportingContent = { Text(stringResource(R.string.settings_turn_dividers_desc)) },
        leadingContent = {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
        },
        trailingContent = {
            Switch(
                checked = showTurnDividers,
                onCheckedChange = { viewModel.setShowTurnDividers(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setShowTurnDividers(!showTurnDividers) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
