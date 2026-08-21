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
     * @param childSessionIds callID → tool.progress metadata.sessionID（#180：Running 期子会话推断）
     * @return 注入后的 parts 列表（无匹配时原样返回原引用，保持引用稳定，
     *         供 combine 管道做 ChatMessage 实例复用判断）
     */
    fun inject(
        parts: List<Part>,
        progressOutputs: Map<String, String>,
        childSessionIds: Map<String, String> = emptyMap(),
    ): List<Part> {
        if (progressOutputs.isEmpty() && childSessionIds.isEmpty()) return parts
        var changed = false
        val result = parts.map { part ->
            if (part is Part.Tool && part.state is ToolState.Running) {
                val output = progressOutputs[part.callId]
                val child = childSessionIds[part.callId]
                val newOutput = output?.takeIf { it.isNotEmpty() }
                val newMetadata = child?.let {
                    val md = (part.state.metadata ?: emptyMap()).toMutableMap()
                    md["sessionID"] = kotlinx.serialization.json.JsonPrimitive(it)
                    md["sessionId"] = kotlinx.serialization.json.JsonPrimitive(it)
                    md.toMap()
                }
                if (newOutput != null || newMetadata != null) {
                    changed = true
                    part.copy(state = part.state.copy(
                        output = newOutput ?: part.state.output,
                        metadata = newMetadata ?: part.state.metadata,
                    ))
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
