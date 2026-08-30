package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * SseEvent 序列 → [MessageWithParts] 装配器（backlog #276 步骤③；MessageApi 历史签名适配）。
 *
 * oc-beacon 的 MessageApi.listMessages 强类型返回 MessagePage——DSH 历史是原始
 * SessionEvent 流，先经 DshHistoryFolder/DshEventMapper 产出 SseEvent（映射是逐事件
 * 无状态变换，§1.5 结论 3），再由本装配器折成分页形状：
 * - MessageUpdated → 建壳/覆盖壳（重放幂等：同 id 原位更新，不重复）；
 * - MessagePartUpdated → 挂到对应 messageId 的 part 列表（保持到达序）；
 * - MessageRemoved → 拆壳（#275 live→history 收敛桥在装配面等价丢弃）；
 * - 其余事件（delta/status/todo…）不参与消息装配。
 *
 * 纯函数 / 无 IO——listMessages（历史分页）与对账回填共用。
 */
object DshMessageAssembler {

    fun assemble(events: List<SseEvent>): List<MessageWithParts> {
        val order = mutableListOf<String>()
        val shells = LinkedHashMap<String, Message>()
        val partsByMessage = LinkedHashMap<String, MutableList<Part>>()
        for (event in events) {
            when (event) {
                is SseEvent.MessageUpdated -> {
                    val id = event.info.id
                    if (id !in shells) order += id
                    shells[id] = event.info
                }
                is SseEvent.MessagePartUpdated ->
                    partsByMessage.getOrPut(event.part.messageId) { mutableListOf() }.add(event.part)
                is SseEvent.MessageRemoved -> {
                    shells.remove(event.messageId)
                    partsByMessage.remove(event.messageId)
                    order.remove(event.messageId)
                }
                else -> Unit // delta/status 等不参与装配（fold 场景整装 part 已终态）
            }
        }
        return order.mapNotNull { id ->
            shells[id]?.let { shell ->
                MessageWithParts(info = shell, parts = partsByMessage[id] ?: emptyList())
            }
        }
    }
}
