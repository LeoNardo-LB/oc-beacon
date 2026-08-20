package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState

/**
 * 把 tool.progress 累积的 output 注入到对应 callID 的 Part.Tool（仅 Running 态）。
 *
 * Pure function —— 在 MessageDataDelegate 的 combine 管道中调用，使所有读取
 * `Part.Tool.state` 的 UI 自动获得运行期输出，无需各卡片单独查询 progress 流。
 *
 * 设计依据：`docs/archive/specs/2026-07-02-shell-streaming-and-patchcard-restyle-design.md` §2.5
 * —— Running.output 为本地增强，tool.success 时 Completed.output（服务器权威）经 message
 * 通道自然覆盖，无冲突。
 */
object ToolProgressOutputInjector {

    /**
     * @param parts 当前消息 parts 列表
     * @param progressOutputs callID → 累积的 progress 输出文本
     * @return 注入后的 parts 列表（无匹配时原样返回原引用，保持引用稳定，
     *         供 combine 管道做 ChatMessage 实例复用判断）
     */
    fun inject(
        parts: List<Part>,
        progressOutputs: Map<String, String>
    ): List<Part> {
        if (progressOutputs.isEmpty()) return parts
        var changed = false
        val result = parts.map { part ->
            if (part is Part.Tool && part.state is ToolState.Running) {
                val output = progressOutputs[part.callId]
                if (!output.isNullOrEmpty()) {
                    changed = true
                    part.copy(state = part.state.copy(output = output))
                } else {
                    part
                }
            } else {
                part
            }
        }
        return if (changed) result else parts
    }
}
