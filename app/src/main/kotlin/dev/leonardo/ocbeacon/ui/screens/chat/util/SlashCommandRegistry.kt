package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R

/**
 * 建议弹窗的斜杠命令定义。
 * @param name 不含 "/" 前缀的命令名
 * @param description 人类可读的描述
 * @param type "server" 命令通过 API 发送，"client" 命令触发本地动作
 */
internal data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String // "server" 或 "client"
)

/** 客户端斜杠命令注册表 —— 从 ChatInputBar.kt 抽取。 */
internal object SlashCommandRegistry {

    /** 镜像原始 opencode TUI 的客户端斜杠命令。 */
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
