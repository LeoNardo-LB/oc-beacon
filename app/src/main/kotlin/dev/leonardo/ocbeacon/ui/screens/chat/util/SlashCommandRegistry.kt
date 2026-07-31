package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R

/**
 * Slash command definition for the suggestion popup.
 * @param name Command name without the "/" prefix
 * @param description Human-readable description
 * @param type "server" commands are sent via API, "client" commands trigger local actions
 */
internal data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String // "server" or "client"
)

/** Client-side slash command registry — extracted from ChatInputBar.kt. */
internal object SlashCommandRegistry {

    /** Client-side slash commands that mirror the original opencode TUI. */
    @Composable
    fun clientCommands(): List<SlashCommand> {
        return listOf(
            SlashCommand("new", stringResource(R.string.cmd_new), "client"),
            SlashCommand("compact", stringResource(R.string.cmd_compact), "client"),
            SlashCommand("fork", stringResource(R.string.cmd_fork), "client"),
            SlashCommand("share", stringResource(R.string.cmd_share), "client"),
            SlashCommand("unshare", stringResource(R.string.cmd_unshare), "client"),
            SlashCommand("undo", stringResource(R.string.cmd_undo), "client"),
            SlashCommand("redo", stringResource(R.string.cmd_redo), "client"),
            SlashCommand("rename", stringResource(R.string.cmd_rename), "client"),
            SlashCommand("shell", stringResource(R.string.cmd_shell_mode), "client"),
        )
    }
}
