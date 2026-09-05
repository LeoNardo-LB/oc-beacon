package dev.leonardo.ocbeacon.ui.screens.chat.input

/**
 * #312④ 命令带图拦截判定（纯逻辑，PlanChipGate 同族）。
 *
 * 服务器契约（dsh-commands CommandInputDescriptor）：命令仅在声明
 * input.images=true 时接受图片；absent/false → executor 拒绝且
 * “capable composers refuse the submission before dispatch”——客户端应在派发前拦截。
 * 命令形态与 ChatScreenBottomBar doSend 分流同语义（前导斜杀、“/ ”转义、
 * 命令名非空）。
 */
internal object SlashCommandGate {

    /**
     * 提取命令名（不含斜杀）；非命令形态（无前导斜杀/“/ ”转义/
     * 命令名空白）→ null（按普通消息发送，图片合法）。
     */
    fun commandNameOf(text: String): String? {
        if (!text.startsWith("/") || text.startsWith("/ ")) return null
        val name = text.removePrefix("/").substringBefore(' ').trim()
        return name.takeIf { it.isNotBlank() }
    }

    /**
     * 拦截判定：命令形态 + 图片附件非空 → true（发送前拦下并提示。
     * 否则放行：无图命令正常执行；非命令文本带图走 prompt 通道）。
     */
    fun blocksImages(text: String, imageCount: Int): Boolean =
        commandNameOf(text) != null && imageCount > 0
}