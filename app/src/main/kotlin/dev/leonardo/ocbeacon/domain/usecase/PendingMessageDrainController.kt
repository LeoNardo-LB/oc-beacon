package dev.leonardo.ocbeacon.domain.usecase

/**
 * 堆积队列手动放行入口（#176/#177/#188 走查修复：UI 层不直接依赖
 * data 层具体管线——SessionListViewModel 经本接口触发；ChatViewModel 的
 * 既有直依赖为历史伤口，不在本批扩散范围）。
 */
interface PendingMessageDrainController {
    /** 会话列表详情对话框「继续发送堆积消息」：空闲会话手动放行队首 1 条。 */
    fun continueFromList(sessionId: String)
}