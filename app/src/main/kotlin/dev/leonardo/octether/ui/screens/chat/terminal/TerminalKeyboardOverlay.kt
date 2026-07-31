package dev.leonardo.octether.ui.screens.chat.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalKeyboardOverlay(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    cursorApp: Boolean,
    onToggleDrawer: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendInput: (String) -> Unit,
    onCtrlC: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Arrow / Home / End sequences depend on DECCKM
    val arrowUp    = if (cursorApp) "\u001BOA" else "\u001B[A"
    val arrowDown  = if (cursorApp) "\u001BOB" else "\u001B[B"
    val arrowRight = if (cursorApp) "\u001BOC" else "\u001B[C"
    val arrowLeft  = if (cursorApp) "\u001BOD" else "\u001B[D"
    val home       = if (cursorApp) "\u001BOH" else "\u001B[H"
    val end        = if (cursorApp) "\u001BOF" else "\u001B[F"

    Surface(
        modifier = modifier,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.dp),
        ) {
            // Row 1: matches Termux default extra keys
            TerminalKeyRow(
                keys = listOf(
                    TerminalKey("ESC", popupLabel = "☰", popupAction = onToggleDrawer) { onSendInput("\u001B") },
                    TerminalKey("/") { onSendInput("/") },
                    TerminalKey("-", popupLabel = "|", popupAction = { onSendInput("|") }) { onSendInput("-") },
                    TerminalKey("HOME") { onSendInput(home) },
                    TerminalKey("\u2191") { onSendInput(arrowUp) },
                    TerminalKey("END") { onSendInput(end) },
                    TerminalKey("PGUP") { onSendInput("\u001B[5~") },
                )
            )
            // Thin divider between rows
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            // Row 2: matches Termux default extra keys
            TerminalKeyRow(
                keys = listOf(
                    TerminalKey("\u21B9") { onSendInput("\t") },
                    TerminalKey("CTRL", active = ctrlLatched, action = onToggleCtrl),
                    TerminalKey("ALT", active = altLatched, action = onToggleAlt),
                    TerminalKey("\u2190") { onSendInput(arrowLeft) },
                    TerminalKey("\u2193") { onSendInput(arrowDown) },
                    TerminalKey("\u2192") { onSendInput(arrowRight) },
                    TerminalKey("PGDN") { onSendInput("\u001B[6~") },
                )
            )
        }
    }
}

internal data class TerminalKey(
    val label: String,
    val active: Boolean = false,
    val popupLabel: String? = null,
    val popupAction: (() -> Unit)? = null,
    val action: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalKeyRow(keys: List<TerminalKey>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            if (index > 0) {
                // Thin vertical divider between keys
                Box(
                    Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .then(
                        if (key.active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        else Modifier
                    )
                    .combinedClickable(
                        onClick = key.action,
                        onLongClick = { key.popupAction?.invoke() }
                    )
            ) {
                Text(
                    text = key.label,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 13.sp
                    ),
                    color = if (key.active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
