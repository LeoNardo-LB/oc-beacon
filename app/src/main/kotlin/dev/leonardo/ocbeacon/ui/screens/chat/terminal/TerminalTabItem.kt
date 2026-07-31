package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.TerminalTabUi
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * A single terminal tab entry rendered inside the session drawer.
 */
@Composable
internal fun TerminalTabItem(
    tab: TerminalTabUi,
    selected: Boolean,
    isAmoled: Boolean,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawerItemShape = ShapeTokens.medium
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(drawerItemShape)
            .then(
                if (isAmoled && selected) {
                    Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED)),
                        drawerItemShape
                    )
                } else Modifier
            )
    ) {
        NavigationDrawerItem(
            label = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = tab.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (tab.state != TerminalTabState.Connected) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = SpacingTokens.SM.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                    )
                                    Text(
                                        text = "Offline",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (tab.state != TerminalTabState.Connected) {
                        IconButton(
                            onClick = onReconnect,
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isAmoled) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.MEDIUM)
                                }
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.terminal_reconnect_tab))
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isAmoled) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                            }
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.terminal_close_tab))
                    }
                }
            },
            selected = selected,
            shape = drawerItemShape,
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.MUTED),
                unselectedContainerColor = Color.Transparent,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                unselectedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
