package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextBreakdown
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocbeacon.ui.screens.chat.util.MessageCount
import dev.leonardo.ocbeacon.ui.screens.chat.util.ProviderModel
import dev.leonardo.ocbeacon.ui.screens.chat.util.SessionTimestamps
import dev.leonardo.ocbeacon.ui.screens.chat.util.cacheHitRate
import dev.leonardo.ocbeacon.ui.screens.chat.util.countMessages
import dev.leonardo.ocbeacon.ui.screens.chat.util.estimateContextBreakdown
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Builds context detail state (token breakdown, cache hit rate, provider info)
 * from message list, token stats, session data, and model config.
 *
 * Extracted from ChatViewModel to isolate context computation logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextDetailDelegate(
    sessionIdFlow: kotlinx.coroutines.flow.Flow<String>,
    messageListState: StateFlow<MessageListState>,
    tokenStatsState: StateFlow<TokenStatsState>,
    sessionsFlow: kotlinx.coroutines.flow.Flow<List<Session>>,
    modelConfigContextWindow: kotlinx.coroutines.flow.Flow<Int>,
    scope: CoroutineScope,
) {
    val state: StateFlow<ContextDetailState> = sessionIdFlow.flatMapLatest { sid ->
        combine(
            messageListState,
            tokenStatsState,
            sessionsFlow,
            modelConfigContextWindow,
        ) { msgList, stats, sessions, contextWindow ->
            val session = sessions.find { it.id == sid }
            buildContextDetailState(msgList.messages, stats, session, contextWindow)
        }
    }.stateIn(
        scope,
        WhileSubscribed5s,
        ContextDetailState()
    )

    companion object {
        fun buildContextDetailState(
            messages: List<ChatMessage>,
            stats: TokenStatsState,
            session: Session?,
            contextWindow: Int,
        ): ContextDetailState {
            val realInput = stats.totalInputTokens
            val breakdown: ContextBreakdown? = if (realInput > 0) {
                val mwp = messages.map { MessageWithParts(it.message, it.parts) }
                estimateContextBreakdown(mwp, realInput)
            } else null
            val messageCount = countMessages(messages.map { it.message })
            val providerModel: ProviderModel? =
                (messages.lastOrNull { it.message is Message.Assistant }?.message as? Message.Assistant)
                    ?.let { ProviderModel(it.providerId, it.modelId) }
            val timestamps: SessionTimestamps? = session?.time
                ?.let { SessionTimestamps(it.created, it.updated) }
            val cacheHitRateVal: Float? = cacheHitRate(stats.totalCacheReadTokens, stats.totalInputTokens)
            return ContextDetailState(
                inputTokens = stats.totalInputTokens,
                outputTokens = stats.totalOutputTokens,
                reasoningTokens = stats.totalReasoningTokens,
                cacheReadTokens = stats.totalCacheReadTokens,
                cacheWriteTokens = stats.totalCacheWriteTokens,
                totalCost = stats.totalCost,
                contextWindow = contextWindow,
                contextTokens = stats.lastContextTokens,
                messageCount = messageCount,
                providerModel = providerModel,
                timestamps = timestamps,
                cacheHitRate = cacheHitRateVal,
                breakdown = breakdown,
            )
        }
    }
}
