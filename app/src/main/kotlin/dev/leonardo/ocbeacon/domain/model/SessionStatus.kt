package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 会话状态 —— 指示会话正在处理还是空闲
 */
@Serializable
sealed class SessionStatus {
    @Serializable
    data object Idle : SessionStatus()
    
    @Serializable
    data object Busy : SessionStatus()
    
    /** 2026-08-14：等待用户回答问题（pending question）——并入状态枚举，
     *  不再用独立的 hasPendingQuestion 布尔标记表达。 */
    @Serializable
    data object Asking : SessionStatus()
    
    @Serializable
    data class Retry(
        val attempt: Int,
        val message: String,
        val next: Long // 下次重试的时间戳
    ) : SessionStatus()
}
