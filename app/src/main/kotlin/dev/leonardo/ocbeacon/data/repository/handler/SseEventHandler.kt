package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * 按类别处理 SSE 事件的策略接口。
 * 每个 handler 处理一部分 SseEvent 类型并更新自身状态。
 */
interface SseEventHandler {
    /**
     * 处理给定事件，按需更新内部状态。
     * @param event 要处理的 SSE 事件
     * @param serverId 事件来源的服务器
     * @return 若此 handler 识别并处理了该事件则返回 true
     */
    fun handle(event: SseEvent, serverId: String): Boolean
}
