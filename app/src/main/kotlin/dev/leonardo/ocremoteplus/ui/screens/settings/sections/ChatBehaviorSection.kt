package dev.leonardo.ocremoteplus.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Vibration
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
import dev.leonardo.ocremoteplus.ui.screens.settings.components.getImageMaxSideDisplayName
import dev.leonardo.ocremoteplus.ui.theme.ListItemTokens
import kotlin.math.roundToInt

@Composable
fun ChatBehaviorSection(
    viewModel: SettingsViewModel,
    onShowMessageCountDialog: () -> Unit,
    onShowRecentDirectoryCountDialog: () -> Unit,
    onShowImageMaxSideDialog: () -> Unit,
    onShowImageQualityDialog: () -> Unit,
    onShowTerminalFontSizeDialog: () -> Unit,
) {
    val initialMessageCount by viewModel.initialMessageCount.collectAsStateWithLifecycle()
    val recentDirectoryCount by viewModel.recentDirectoryCount.collectAsStateWithLifecycle()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsStateWithLifecycle()
    val hapticFeedback by viewModel.hapticFeedback.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsStateWithLifecycle()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsStateWithLifecycle()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsStateWithLifecycle()
    val terminalFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val switchColors = SwitchDefaults.colors()

    SectionHeader(stringResource(R.string.settings_section_chat_behavior))

    // Initial message count
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_initial_messages)) },
        supportingContent = { Text("$initialMessageCount") },
        leadingContent = {
            Icon(Icons.Default.Storage, contentDescription = stringResource(R.string.a11y_settings_initial_messages))
        },
        modifier = Modifier.clickable { onShowMessageCountDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Recent directory count (quick new-session dialog)
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_recent_directory_count)) },
        supportingContent = { Text(stringResource(R.string.settings_recent_directory_count_desc)) },
        trailingContent = { Text("$recentDirectoryCount") },
        leadingContent = {
            Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.a11y_settings_recent_directory_count))
        },
        modifier = Modifier.clickable { onShowRecentDirectoryCountDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Confirm before send
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_confirm_send)) },
        supportingContent = { Text(stringResource(R.string.settings_confirm_send_desc)) },
        leadingContent = {
            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.a11y_settings_confirm_send))
        },
        trailingContent = {
            Switch(
                checked = confirmBeforeSend,
                onCheckedChange = { viewModel.setConfirmBeforeSend(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setConfirmBeforeSend(!confirmBeforeSend) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Haptic feedback
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_haptic_feedback)) },
        supportingContent = { Text(stringResource(R.string.settings_haptic_feedback_desc)) },
        leadingContent = {
            Icon(Icons.Default.Vibration, contentDescription = stringResource(R.string.a11y_settings_haptic_feedback))
        },
        trailingContent = {
            Switch(
                checked = hapticFeedback,
                onCheckedChange = { viewModel.setHapticFeedback(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setHapticFeedback(!hapticFeedback) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Keep screen on
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
        supportingContent = { Text(stringResource(R.string.settings_keep_screen_on_desc)) },
        leadingContent = {
            Icon(Icons.Default.ScreenLockPortrait, contentDescription = stringResource(R.string.a11y_settings_keep_screen_on))
        },
        trailingContent = {
            Switch(
                checked = keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable { viewModel.setKeepScreenOn(!keepScreenOn) }.padding(ListItemTokens.ContentPaddingMedium)
    )

    // Optimize image attachments
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_compress_images)) },
        supportingContent = { Text(stringResource(R.string.settings_compress_images_desc)) },
        leadingContent = {
            Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = stringResource(R.string.a11y_settings_compress_images))
        },
        trailingContent = {
            Switch(
                checked = compressImageAttachments,
                onCheckedChange = { viewModel.setCompressImageAttachments(it) },
                colors = switchColors
            )
        },
        modifier = Modifier.clickable {
            viewModel.setCompressImageAttachments(!compressImageAttachments)
        }.padding(ListItemTokens.ContentPaddingMedium)
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_compress_images_max_side)) },
        supportingContent = { Text(getImageMaxSideDisplayName(imageAttachmentMaxLongSide)) },
        leadingContent = {
            Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = stringResource(R.string.a11y_settings_image_max_side))
        },
        modifier = Modifier.clickable(enabled = compressImageAttachments) { onShowImageMaxSideDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_compress_images_quality)) },
        supportingContent = {
            Text(stringResource(R.string.settings_compress_images_quality_value, imageAttachmentWebpQuality))
        },
        leadingContent = {
            Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = stringResource(R.string.a11y_settings_image_quality))
        },
        modifier = Modifier.clickable(enabled = compressImageAttachments) { onShowImageQualityDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_terminal_font_size)) },
        supportingContent = {
            Text(stringResource(R.string.settings_terminal_font_size_value, terminalFontSize.roundToInt()))
        },
        leadingContent = {
            Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.a11y_settings_terminal_font_size))
        },
        modifier = Modifier.clickable { onShowTerminalFontSizeDialog() }.padding(ListItemTokens.ContentPaddingMedium)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
