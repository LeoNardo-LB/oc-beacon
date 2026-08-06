package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord

/**
 * 核对纯函数：判定哪些待处理 prompt 已足够陈旧、可视为丢失，
 * 应作为失败展示给用户。
 *
 * 策略——时间戳覆盖：
 *  1. [PendingPromptRecord.messageId] 出现在 [authoritative] 中的待处理
 *     已被确认 → 永不丢失。
 *  2. 早于 [minimumAgeMs] **且** "已被覆盖"（服务器已投递任何
 *     `time.created >= pending.createdAt` 的消息）的待处理为丢失——
 *     服务器已越过发送点却从未回显该 prompt。
 *  3. 否则保留该待处理（太新，或服务器尚未追上）。
 *
 * 此方法与格式无关：与上游 v1.7.0（比较 ULID id 范围）不同，
 * 它适用于我们的 `"pending-<uuid>"` id，因为它依据时间戳而非 id
 * 排序——与 MessageDataDelegate 中现有的确认逻辑一致。
 *
 * @param pending 待核对的候选 pending prompt。
 * @param authoritative 服务器当前该会话的消息列表。
 * @param now 当前 epoch 毫秒（用于计算年龄）。
 * @param minimumAgeMs 已覆盖的待处理被判定为丢失前的最小年龄。
 * @return 被视为丢失的 pending 消息 id 集合。
 */
fun missingPendingPromptIds(
    pending: List<PendingPromptRecord>,
    authoritative: List<Message>,
    now: Long,
    minimumAgeMs: Long,
): Set<String> {
    if (pending.isEmpty()) return emptySet()
    val confirmedIds = authoritative.asSequence().map { it.id }.toSet()
    // "已覆盖" = 服务器投递了 created 时间戳大于等于待处理发送时间的任何消息。
    // 通过最大 created 时间戳跟踪，避免对每个 pending 记录扫描整个 authoritative 列表。
    val maxCreated = authoritative.maxOfOrNull { it.time.created } ?: return emptySet()
    return pending
        .asSequence()
        .filter { record ->
            record.messageId !in confirmedIds &&
                (now - record.createdAt) >= minimumAgeMs &&
                maxCreated >= record.createdAt
        }
        .map { it.messageId }
        .toSet()
}
