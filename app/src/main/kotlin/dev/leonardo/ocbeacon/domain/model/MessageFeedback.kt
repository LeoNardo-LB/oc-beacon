package dev.leonardo.ocbeacon.domain.model

/**
 * #310② 消息反馈 👍/👎（DSH messageFeedback 域）领域模型。
 *
 * wire 契约钉死 2026-09-05（docs/research/2026-09-05-310-wire-contracts.md §②，
 * 服务端 dsh-message-feedback/types.d.ts 取证）：
 * - 'messageFeedback/put' {sessionId,messageId,rating,note?,ifVersion:version|null} → MessageFeedbackItem
 * - 'messageFeedback/delete' {sessionId,messageId,ifVersion} → {absent:true} 幂等
 * - 'messageFeedback/list' {sessionId} → {items:[MessageFeedbackItem]}
 * - 业务失败码：session-not-found / target-not-found / version-conflict(带 current) /
 *   note-blank / note-too-large；业务拒绝驱 RPC value（非信封 error），由 DshApiClient 解包为密封结果。
 *
 * [version] 是 CAS 不透明 token（zod UUID）——每次物理写入替换，仅作相等比较；
 * 时间戳为 Unix epoch 毫秒（Host 分配）。
 */

/** 总体评价闭集词汇（wire 两值 positive/negative）。 */
enum class MessageFeedbackRating(val wire: String) {
    Positive("positive"),
    Negative("negative"),
    ;

    companion object {
        /** 非法/未知串→ null（容错跳过，不冒充默认值）。 */
        fun fromWire(raw: String?): MessageFeedbackRating? =
            entries.firstOrNull { it.wire == raw }
    }
}

/** 单条消息反馈快照（服务器权威值 + CAS token）。 */
data class MessageFeedbackItem(
    val messageId: String,
    val rating: MessageFeedbackRating,
    val note: String? = null,
    /** CAS 不透明 token：未评断言用 null，已观察到的项作比较用。 */
    val version: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 点击裁决（纯逻辑，单测 MessageFeedbackTest）：
 * 未评→ put（ifVersion=null 断言无既有项）；已同向→ delete 撤销；换向→ put 换向。
 */
sealed interface MessageFeedbackAction {
    data class Put(
        val rating: MessageFeedbackRating,
        /** 观察到的 version；未评为 null（web 先例 ifVersion=observed?.version??null）。 */
        val ifVersion: String?,
    ) : MessageFeedbackAction

    data class Delete(val ifVersion: String) : MessageFeedbackAction
}

object MessageFeedbackToggle {
    fun decide(observed: MessageFeedbackItem?, desired: MessageFeedbackRating): MessageFeedbackAction = when {
        observed == null -> MessageFeedbackAction.Put(desired, ifVersion = null)
        observed.rating == desired -> MessageFeedbackAction.Delete(ifVersion = observed.version)
        else -> MessageFeedbackAction.Put(desired, ifVersion = observed.version)
    }
}

/**
 * put 密封结果：成功 / version 冲突（带服务器权威 current 供重同步）/
 * 其他业务失败（code 保留原串；传输/unsupported 走 Result.failure 不进此型）。
 */
sealed interface MessageFeedbackPutResult {
    data class Success(val item: MessageFeedbackItem) : MessageFeedbackPutResult

    data class VersionConflict(val current: MessageFeedbackItem?) : MessageFeedbackPutResult

    data class Failure(val code: String?, val reason: String) : MessageFeedbackPutResult
}

/**
 * delete 密封结果：幂等成功（absent 后置条件）/ version 冲突 / 其他业务失败。
 */
sealed interface MessageFeedbackDeleteResult {
    data object Absent : MessageFeedbackDeleteResult

    data class VersionConflict(val current: MessageFeedbackItem?) : MessageFeedbackDeleteResult

    data class Failure(val code: String?, val reason: String) : MessageFeedbackDeleteResult
}
