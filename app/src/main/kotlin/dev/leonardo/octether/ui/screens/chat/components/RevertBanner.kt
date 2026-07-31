package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.octether.ui.screens.chat.util.performHaptic
import dev.leonardo.octether.ui.theme.LocalAmoledMode
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.AlphaTokens

/**
 * Banner shown when messages have been reverted.
 * Tapping restores (redo) the reverted messages.
 */
@Composable
internal fun RevertBanner(onRedo: () -> Unit) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val isAmoled = LocalAmoledMode.current
    Surface(
        shape = ShapeTokens.medium,
        color = if (isAmoled) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = AlphaTokens.MEDIUM),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { performHaptic(hapticView, hapticOn); onRedo() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.a11y_icon_revert),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_messages_reverted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.chat_tap_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = AlphaTokens.MEDIUM)
                )
            }
            Icon(
                Icons.Default.Restore,
                contentDescription = stringResource(R.string.chat_restore),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
