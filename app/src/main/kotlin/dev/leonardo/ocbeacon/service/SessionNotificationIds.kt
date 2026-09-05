package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.PendingInteractionKind

/**
 * 会话事件通知 kind（#320 收口）：turn 完成 / 待审批 / 待提问 / 错误。
 *
 * [idOffset] 是通知 id 空间的系统侧持久契约——同 (server, session, kind) 的
 * 通知 id 恒定，重复发布为原位更新不堆叠。值与既有
 * AppNotificationManager.eventNotificationId 的 offset 分槽（0/1000/2000/3000）
 * 逐字一致（迁移不改值），契约由 SessionNotificationIdsTest 锁死。
 */
enum class SessionNotificationKind(val idOffset: Int) {
    TURN_COMPLETE(0),
    PERMISSION(1000),
    QUESTION(2000),
    ERROR(3000);

    companion object {
        /**
         * #320：待交互 kind（#311 PendingInteractionStore）→ 通知 kind 槽位。
         * plan-review 呈现归并 question 同点（isQuestionFamily 同规则）。
         */
        fun forPendingInteraction(kind: PendingInteractionKind): SessionNotificationKind = when (kind) {
            PendingInteractionKind.APPROVAL -> PERMISSION
            PendingInteractionKind.QUESTION, PendingInteractionKind.PLAN_REVIEW -> QUESTION
        }
    }
}

/**
 * 会话事件通知 id / 标题纯函数（#320，JVM 可测——Android 侧发布归
 * AppNotificationManager，mockk 边界之外的映射逻辑收口于此）。
 */
object SessionNotificationIds {

    /** (serverId, sessionId, kind) → 稳定通知 id（FNV-1a 32 位 + kind 槽位）。 */
    fun of(serverId: String, sessionId: String, kind: SessionNotificationKind): Int =
        stableHashOf(serverId, sessionId) + kind.idOffset

    /**
     * FNV-1a 32 位稳定 hash——与既有 AppNotificationManager.stableHash 同算法
     * （实现收口于此，AppNotificationManager 委托本处；值不变）。
     * 相比字符串拼接 + hashCode()：无拼接歧义（"a"+"bc" 与 "ab"+"c" 不同值），
     * 且跨 JVM/平台行为一致。
     */
    fun stableHashOf(vararg parts: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (part in parts) {
            for (i in part.indices) {
                hash = (hash xor part[i].code) * 0x01000193
            }
        }
        return hash
    }

    /**
     * 通知标题：会话 title 回退会话 id（#320 spec——title 缺失时用 id 而非
     * 泛化文案，用户可直接辨识是哪个会话）。
     */
    fun notificationTitle(sessionTitle: String?, sessionId: String): String =
        sessionTitle?.trim()?.takeIf { it.isNotEmpty() } ?: sessionId
}
