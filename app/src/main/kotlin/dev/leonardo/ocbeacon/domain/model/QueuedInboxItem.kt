package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * DSH 排队收件箱项（session/queue 帧整快照项）。
 *
 * wire 形状（官方 QueueDock / queue-mirror 队列帧 item 规范：
 * dsh-client-runtime client.js SessionQueueMirror.replace）：
 * {id, placement: queued|steering|context, message:{content:[...], role, source}}
 * - [placement]：queued（排队待发）/ steering（注入进行中轮次）/ context（会话上下文）；
 *   仅 queued 行接受队列变更（updateQueue 契约——agent 侧裁决）；
 * - [preview]：首个 text 块截断预览（渲染行）；
 * - [text]：整块纯文本（编辑可写）；含非文本块则 null（编辑不可用——官方 textOf）。
 *
 * 语义：瞬态快照（不入历史/不重放）；last-wins 整替换（空集删键、subscribed 清空重推）。
 */
@Serializable
data class QueuedInboxItem(
    val id: String,
    val placement: String,
    val preview: String = "",
    /** 完整可编辑文本；null = 含非文本块（编辑不可用）。 */
    val text: String? = null,
) {
    val isQueuedPlacement: Boolean get() = placement == PLACEMENT_QUEUED

    companion object {
        const val PLACEMENT_QUEUED = "queued"
        const val PLACEMENT_STEERING = "steering"
        const val PLACEMENT_CONTEXT = "context"
    }
}

/** QueueDock 变更动作（对齐官方 QueueAction：edit/remove/steer）。 */
enum class QueueActionKind { EDIT, REMOVE, STEER }

/**
 * updateQueue RPC 结果（错误码区分提示：steer-unavailable / queue-item-not-found /
 * agent-busy（子代理会话只读）——DshEnvelope 39 码闭集内）。
 */
sealed interface QueueMutationResult {
    data object Accepted : QueueMutationResult
    /** steer 仅 running + next-turn 有效；其余时机服务器拒（steer-unavailable）。 */
    data object SteerUnavailable : QueueMutationResult
    data object QueueItemNotFound : QueueMutationResult
    /** 子代理会话拒绝队列变更（agent-busy；UI 侧只读不达此分支）。 */
    data object Busy : QueueMutationResult
    data class Failed(val message: String) : QueueMutationResult
}