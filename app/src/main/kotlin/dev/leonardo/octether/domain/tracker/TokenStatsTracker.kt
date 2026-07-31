package dev.leonardo.octether.domain.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val contextWindow: Int = 0,
        val lastContextTokens: Int = 0,
    )

    private val _stats = MutableStateFlow(TokenStats())
    val stats: StateFlow<TokenStats> = _stats

    fun update(block: TokenStats.() -> TokenStats) {
        _stats.value = _stats.value.block()
    }

    fun reset() {
        _stats.value = TokenStats()
    }
}
