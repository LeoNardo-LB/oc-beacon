package dev.leonardo.ocbeacon.domain.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStatsTracker @Inject constructor() {
    data class TokenStats(
        val totalCost: Double = 0.0,
        val totalInputTokens: Int = 0,
        val totalOutputTokens: Int = 0,
        val totalReasoningTokens: Int = 0,
        val totalCacheReadTokens: Int = 0,
        val totalCacheWriteTokens: Int = 0,
        val lastContextTokens: Int = 0,
    )

    private val _stats = MutableStateFlow(TokenStats())
    val stats: StateFlow<TokenStats> = _stats

    fun update(block: TokenStats.() -> TokenStats) {
        // #134（D2-L39）：裸读-改-写非 CAS——并发 update（多会话 token 事件同时到达）
        // 会丢更新；StateFlow.update 为 CAS 循环，原子合并
        _stats.update { it.block() }
    }

    fun reset() {
        _stats.value = TokenStats()
    }
}
