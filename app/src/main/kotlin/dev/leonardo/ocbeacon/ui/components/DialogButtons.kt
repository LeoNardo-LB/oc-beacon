package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens

/**
 * Role of a button inside a dialog.
 *
 * - [Primary]:   Main action (confirm, save, create). Filled Button with primary color.
 * - [Secondary]: Cancel / dismiss. OutlinedButton with Material 3 default colors.
 * - [Danger]:    Destructive action (delete, revert). Filled Button with error color.
 */
enum class DialogButtonRole {
    Primary,
    Secondary,
    Danger,
}

/**
 * Unified dialog button row.
 *
 * Layout rules:
 * - 1 button: single Row, right-aligned
 * - 2 buttons: Row, horizontal, right-aligned
 * - 3+ buttons: Column, vertical, full-width
 *
 * @param buttons List of (label, role, onClick) triples.
 */
@Composable
fun DialogButtons(
    buttons: List<Triple<String, DialogButtonRole, () -> Unit>>,
) {
    if (buttons.size <= 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonTokens.RowSpacing.dp, Alignment.End),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ButtonTokens.StackSpacing.dp),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    role: DialogButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val contentPadding = if (compact) ButtonTokens.CompactPadding else ButtonDefaults.ContentPadding
    when (role) {
        DialogButtonRole.Primary -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonTokens.filledColors(),
                border = ButtonTokens.amoledBorder(),
                contentPadding = contentPadding,
            ) {
                Text(text)
            }
        }
        DialogButtonRole.Secondary -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                contentPadding = contentPadding,
            ) {
                Text(text)
            }
        }
        DialogButtonRole.Danger -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonTokens.dangerColors(),
                border = ButtonTokens.amoledBorder(),
                contentPadding = contentPadding,
            ) {
                Text(text)
            }
        }
    }
}
