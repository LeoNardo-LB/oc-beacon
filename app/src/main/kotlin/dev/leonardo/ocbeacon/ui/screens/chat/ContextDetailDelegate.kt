package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.TurnDetailInput
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
 * 从消息列表、token 统计、会话数据和模型配置
 * 构建上下文详情状态（token 分布、缓存命中率、provider 信息）。
 *
 * 从 ChatViewModel 提取以隔离上下文计算逻辑。
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
                // DSH 子代理区：tokenUsage 投影累计 tokens + subagentTiming 派生活跃时长
                //（OpenCode 会话两字段恒 null → ContextDetailDialog 不渲染该区）。
                subagentTokens = session?.tokenUsage,
                subagentActiveDurationMs = session?.subagentTiming?.activeDurationMs,
                // DSH 上下文环投影（Web 语义：分子 projectedTokens ?? pressureTokens，分母投影 window；
                // 任一缺席 → 整环不渲染）。OpenCode 恒 null → 走既有 llm.models 路径。
                projectionUsedTokens = session?.contextPressure?.let { p ->
                    p.projectedTokens ?: p.pressureTokens
                },
                projectionContextWindow = session?.contextPressure?.contextWindow,
                projectionBreakdown = session?.contextBreakdown,
                projectionSessionStats = session?.sessionStats,
                // 批3 统计弹窗：逐轮明细装配输入（倒序 = 最新在上）
                turnDetailInputs = buildTurnDetailInputs(messages),
            )
        }

        /**
         * 批3 统计弹窗：从消息列表构造逐轮明细装配输入（spec US#25-27）。
         *
         * 分组规则：user 消息开启一轮（synthetic 注入不算轮锚，跳过），其后
         * assistant 消息归入该轮；轮内无 assistant 则整轮跳过。无轮锚的
         * assistant（出现在首条 user 之前）丢弃。
         *
         * 每轮：
         * - serverTurn = 首条 assistant 的服务器 turn 号（DSH 有；OpenCode null）；
         * - clientOrdinal = 本构造列表中的序号（按时间正序从 1 起）；
         * - durationMs = 轮内全部 assistant 完结时 max(completed) - min(created)（<=0 归 null）；
         * - tokens = 各 assistant tokens 逐桶求和（total 求和或全 null 时 null）；
         * - stepCount = 轮内 assistant 消息数；toolCallCount = 轮内 Part.Tool 数；
         * - cost = 轮内 cost 求和（全 null 则 null）；
         * - modelId/providerId 取首条 assistant；TTFT/速度当前无逐轮源 → null。
         *
         * 输出按时间倒序（最新在上），UI 直接懒加载渲染。
         */
        private fun buildTurnDetailInputs(messages: List<ChatMessage>): List<TurnDetailInput> {
            // ① 分组（保持消息顺序；轮锚 = 非 synthetic 的 user 消息）
            val turns = ArrayList<List<ChatMessage>>()
            var current: MutableList<ChatMessage>? = null
            for (msg in messages) {
                if (msg.isSynthetic) continue
                when (msg.message) {
                    is Message.User -> {
                        current = mutableListOf(msg)
                        turns.add(current)
                    }
                    is Message.Assistant -> current?.add(msg)
                }
            }
            // ② 聚合（编号按时间正序从 1 起；输出倒序）
            val out = ArrayList<TurnDetailInput>(turns.size)
            var ordinal = 0
            for (turn in turns) {
                val assistants = turn.mapNotNull { m ->
                    (m.message as? Message.Assistant)?.let { m to it }
                }
                if (assistants.isEmpty()) continue
                ordinal++
                val first = assistants.first().second
                val durationMs: Long? = if (assistants.all { it.second.time.completed != null }) {
                    val maxCompleted = assistants.maxOf { it.second.time.completed!! }
                    val minCreated = assistants.minOf { it.second.time.created }
                    (maxCompleted - minCreated).takeIf { it > 0 }
                } else null
                val tokenParts = assistants.mapNotNull { it.second.tokens }
                val tokens: Message.Assistant.Tokens? = tokenParts.takeIf { it.isNotEmpty() }?.let { list ->
                    Message.Assistant.Tokens(
                        input = list.sumOf { it.input },
                        output = list.sumOf { it.output },
                        reasoning = list.sumOf { it.reasoning },
                        cache = Message.Assistant.Tokens.Cache(
                            read = list.sumOf { it.cache.read },
                            write = list.sumOf { it.cache.write },
                        ),
                        total = list.map { it.total }
                            .takeIf { totals -> totals.any { it != null } }
                            ?.sumOf { it ?: 0 },
                    )
                }
                val costs = assistants.map { it.second.cost }
                val cost = costs
                    .takeIf { list -> list.any { it != null } }
                    ?.sumOf { it ?: 0.0 }
                out.add(
                    TurnDetailInput(
                        serverTurn = first.turnNumber,
                        clientOrdinal = ordinal,
                        durationMs = durationMs,
                        tokens = tokens,
                        stepCount = assistants.size,
                        toolCallCount = turn.sumOf { m -> m.parts.count { it is Part.Tool } },
                        cost = cost,
                        modelId = first.modelId,
                        providerId = first.providerId,
                        // TTFT / tokens·s 当前无逐轮源（US#32）→ 保持 null，UI 整项隐藏
                    )
                )
            }
            return out.asReversed()
        }
    }
}