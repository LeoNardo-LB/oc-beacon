package dev.leonardo.octether.ui.screens.chat.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.screens.chat.util.SlashCommand

/**
 * Slash command suggestion popup shown when user types "/".
 *
 * @param commands Filtered slash commands to display.
 * @param onSkillClick Called when a skill-type command is clicked — caller handles input text update.
 * @param onCommandClick Called when a non-skill command is clicked — caller clears text and fires the command.
 */
@Composable
internal fun SlashCommandSuggestions(
    commands: List<SlashCommand>,
    onSkillClick: (SlashCommand) -> Unit,
    onCommandClick: (SlashCommand) -> Unit
) {
    AnimatedVisibility(
        visible = commands.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val configuration = LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.4f).dp

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 4.dp)
        ) {
            items(commands, key = { it.name }) { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (cmd.type == "skill") {
                                onSkillClick(cmd)
                            } else {
                                onCommandClick(cmd)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "/${cmd.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (cmd.type == "skill") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    if (cmd.type == "skill") {
                        Text(
                            text = "skill",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (cmd.description != null) {
                        Text(
                            text = cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
