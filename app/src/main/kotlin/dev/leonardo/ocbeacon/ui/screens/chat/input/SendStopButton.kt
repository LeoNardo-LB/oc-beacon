package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.components.BreathingCircleIndicator
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * Send / Stop button — tap to send or stop, long-press toggles shell mode.
 *
 * @param showStop whether the stop icon should be shown (busy with no text)
 * @param canSend whether sending is currently allowed
 * @param isSending whether a message is currently being sent
 * @param isShellMode whether shell mode is active
 * @param isAmoled whether AMOLED theme is active
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SendStopButton(
    showStop: Boolean,
    canSend: Boolean,
    isSending: Boolean,
    isShellMode: Boolean,
    isAmoled: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
    onInputModeChange: (ChatInputMode) -> Unit
) {
    Box(
        modifier = Modifier
            .testTag("chat-send")
            .size(44.dp)
            .clip(ShapeTokens.largeMedium)
            .background(
                if (showStop) {
                    MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.SELECTED)
                } else if (isShellMode && !isSending) {
                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.FAINT)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                }
            )
            .then(
                if (showStop) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MEDIUM),
                        shape = ShapeTokens.largeMedium,
                    )
                } else if (isShellMode && !isSending) {
                    Modifier.border(
                        width = if (isAmoled) 1.2.dp else 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) AlphaTokens.AMOLED else AlphaTokens.HIGH),
                        shape = ShapeTokens.largeMedium,
                    )
                } else if (isAmoled) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED),
                        shape = ShapeTokens.largeMedium,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                        shape = ShapeTokens.largeMedium,
                    )
                }
            )
            .combinedClickable(
                onClick = {
                    if (showStop) {
                        onStop()
                    } else if (canSend) {
                        onSend()
                    }
                },
                onLongClick = {
                    if (!showStop) {
                        onInputModeChange(
                            if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                        )
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showStop) {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.chat_stop),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
        } else if (isSending) {
            BreathingCircleIndicator(
                size = 20.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = if (isShellMode) {
                    stringResource(R.string.chat_send_shell)
                } else {
                    stringResource(R.string.chat_send)
                },
                modifier = Modifier.size(20.dp),
                tint = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else if (isShellMode && isAmoled && !isSending) {
                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                }
            )
        }
    }
}
