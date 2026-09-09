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
    val type: String, // "server"、"client" 或 "skill"（#324④ skills 触发组）
    /** 打字路径携带的自由参数（/rename testx → "testx"）；面板 tap 恒 null。2026-09-09 发现通道级缺失：typed 分支曾丢弃 commandArgs。 */
    val args: String? = null,
    /** 需要自由输入参数（DSH commands/list 的 input.hint 非空）：选择即填入输入框而非直接执行。 */
    val requiresInput: Boolean = false,
    /** #324④：skill 专属——模型可自主调用标识（skills/list modelInvocable）。 */
    val modelInvocable: Boolean = false,
)

/** 客户端斜杠命令注册表 —— 从 ChatInputBar.kt 抽取。 */
internal object SlashCommandRegistry {

    /**
     * 客户端命令名集（顺序 = 建议列表展示序）。单一真相源：建议列表
     * （[clientCommands]）与发送缝分流（ChatScreenBottomBar doSend）共源，
     * 防两处漂移。2026-09-09（G2-① 根修）增设——打字路径的客户端命令
     * 此前静默落 prompt 通道喂模型。
     */
    val clientCommandNames: List<String> = listOf(
        "new", "compact", "fork", "share", "unshare", "undo", "redo", "rename", "shell",
    )

    /** 镜像原始 opencode TUI 的客户端斜杠命令。 */
    @Composable
    fun clientCommands(): List<SlashCommand> = clientCommandNames.map { name ->
        SlashCommand(name, descriptionFor(name), "client")
    }

    @Composable
    private fun descriptionFor(name: String): String? = when (name) {
        "new" -> stringResource(R.string.cmd_new)
        "compact" -> stringResource(R.string.cmd_compact)
        "fork" -> stringResource(R.string.cmd_fork)
        "share" -> stringResource(R.string.cmd_share)
        "unshare" -> stringResource(R.string.cmd_unshare)
        "undo" -> stringResource(R.string.cmd_undo)
        "redo" -> stringResource(R.string.cmd_redo)
        "rename" -> stringResource(R.string.cmd_rename)
        "shell" -> stringResource(R.string.cmd_shell_mode)
        else -> null
    }
}