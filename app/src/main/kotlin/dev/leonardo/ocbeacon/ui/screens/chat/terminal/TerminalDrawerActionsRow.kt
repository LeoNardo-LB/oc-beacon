package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Bottom action row of the terminal session drawer: "New Tab" and "Keyboard" buttons.
 */
@Composable
internal fun TerminalDrawerActionsRow(
    onNewTab: () -> Unit,
    onShowKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
    ) {
        Button(
            onClick = onNewTab,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            colors = ButtonTokens.filledColors(),
            border = ButtonTokens.amoledBorder(),
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.a11y_icon_add_tab))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.terminal_new_tab))
        }
        Button(
            onClick = onShowKeyboard,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            colors = ButtonTokens.filledColors(),
            border = ButtonTokens.amoledBorder(),
        ) {
            Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.a11y_icon_keyboard_toggle))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.terminal_keyboard))
        }
    }
}
