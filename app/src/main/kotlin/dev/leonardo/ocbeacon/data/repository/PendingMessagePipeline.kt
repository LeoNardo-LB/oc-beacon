package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import dev.leonardo.ocbeacon.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "PendingMsgPipeline"

/**
 * 堆积消息推进管线（2026-08-20 设计定稿；2026-08-21 #176/#177 状态补偿扩展，
 * spec: docs/specs/2026-08-21-queue-drain-state-compensation-design.md）。
 *
 * 触发规则（2026-08-21 起边沿触发 + 状态补偿并存）：
 * - 保留边沿：任何一次**自然成功 turn 结束**（Busy→Idle 且事件为 SSE 自然
 *   成功信号）→ 发送该会话队首 1 条（[onNaturalTurnEnd]，接线不变）。
 * - 新增状态补偿（修三断点：#176 TOCTOU 边沿错过 / POST 失败不动点 /
 *   RestValidation(Idle) 不在白名单）：**FSM Idle + 队列非空 → drain**。
 *   三触发器汇聚 [drainIfIdle]：
 *   T1 [start] 心跳每 [COMPENSATION_HEARTBEAT_MS] 扫有堆积的会话集合；
 *   T2 [onEnqueued] 入队落库后即时检查（#176 精确路径）；
 *   T3 [start] 收集 statusFlow——任意来源（自然结束/L3/L4/force-complete）
 *   落 Idle 即检查。
 * - 护栏：Busy/无状态跳过（未知≠Idle，由 L4 补态后 T3 接手）；
 *   待答问题/权限跳过；服务器归属未知跳过。
 *
 * 发送语义：
 * - peek → POST → 成功才 delete（at-least-once：POST 成功但 delete 失败的
 *   极端场景宁可重复发送也不丢失用户文本）；
 * - 发送失败：条目留在队首，心跳 5s 静默无限重试（用户定案 2026-08-21）；
 * - POST 成功后 onClientSendParts 置 Busy（与 ChatSendDelegate 一致），
 *   该 turn 自然结束时流水线自动推进下一条；
 * - 模型/agent/variant 均不带（用会话/服务器默认）——入队时不快照 UI 配置。
 *
 * 并发防护：会话级 in-flight 去重（[drainingSessions] 暴露给 UI 标记
 * 「发送中」并锁定该条的编辑/删除）。
 */
@Singleton
class PendingMessagePipeline @Inject constructor(
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val pendingMessageRepository: PendingMessageRepository,
    // Provider 打破 EventDispatcher → ChatRepositoryImpl → EventDispatcher 循环
    private val sendMessageUseCaseProvider: Provider<SendMessageUseCase>,
    private val sessionStateService: SessionStateService,
) {
    /** 正在发送堆积消息的会话集合（UI「发送中」状态源）。 */
    private val _drainingSessions = MutableStateFlow<Set<String>>(emptySet())
    val drainingSessions: StateFlow<Set<String>> = _drainingSessions

    private val draining = ConcurrentHashMap<String, Boolean>()

    /** 状态补偿驱动只启动一次（EventDispatcher init 随首个连接调用）。 */
    private val started = AtomicBoolean(false)

    /**
     * 启动状态补偿驱动（幂等）：
     * - T1 心跳：周期扫描有堆积的会话，Idle 即 drain（POST 失败无限重试源）；
     * - T3 Idle 观察：statusFlow 任意来源落 Idle → drain（断连 L3/L4 恢复
     *   的 RestValidation(Idle) 不经过 naturalTurnEnd 白名单，由这里补上）。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // T1 心跳
        appScope.launch {
            while (isActive) {
                delay(COMPENSATION_HEARTBEAT_MS)
                compensateAll()
            }
        }
        // T3 Idle 观察
        appScope.launch {
            sessionStateService.statusFlow.collect { states ->
                for (sessionId in states.keys) {
                    drainIfIdle(sessionId)
                }
            }
        }
    }

    /** T1 心跳体：全量扫描有堆积的会话（DB 异常不杀心跳循环）。 */
    private suspend fun compensateAll() {
        val sessions = try {
            pendingMessageRepository.sessionIdsWithPending()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "compensation heartbeat failed to read queue: " + e.message)
            return
        }
        for (sessionId in sessions) {
            drainIfIdle(sessionId)
        }
    }

    /**
     * 状态补偿统一入口：FSM Idle + 队列非空 → drain。
     * Busy/无状态/待答/归属未知一律跳过（in-flight 去重在 launchDrain 内）。
     */
    private fun drainIfIdle(sessionId: String) {
        val status = sessionStateService.statusFlow.value[sessionId]
        if (status !is SessionStatus.Idle) {
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                AppLogger.d(TAG, "compensation skip (not Idle: " + (status?.let { it::class.simpleName } ?: "no-state") + "): " + sessionId)
            }
            return
        }
        if (sessionStateService.hasPendingUserInput(sessionId)) {
            AppLogger.d(TAG, "compensation skip (pending user input): " + sessionId)
            return
        }
        val serverId = sessionStateService.serverIdFor(sessionId)
        if (serverId == null) {
            AppLogger.d(TAG, "compensation skip (no server ownership): " + sessionId)
            return
        }
        launchDrain(sessionId, serverId)
    }

    /** T2：入队落库后的即时补偿检查（#176：turn 已在入队前结束的场景）。 */
    fun onEnqueued(sessionId: String) {
        drainIfIdle(sessionId)
    }

    /** 自然成功 turn 结束回调入口（SessionStateService.naturalTurnEndListener）。 */
    fun onNaturalTurnEnd(sessionId: String, serverId: String?) {
        if (serverId == null) {
            AppLogger.w(TAG, "natural turn end without server ownership, skip drain: " + sessionId)
            return
        }
        launchDrain(sessionId, serverId)
    }

    /** 面板「继续」按钮：空闲会话手动放行队首 1 条。 */
    fun continueNow(sessionId: String, serverId: String) = launchDrain(sessionId, serverId)

    /**
     * 会话列表详情对话框「继续发送堆积消息」（#177 手动入口）：
     * 服务器归属经 FSM 解析；无归属（未连接/未知）仅告警跳过。
     * 语义同 [continueNow]——手动放行，不做 Idle 门槛。
     */
    fun continueFromList(sessionId: String) {
        val serverId = sessionStateService.serverIdFor(sessionId)
        if (serverId == null) {
            AppLogger.w(TAG, "manual continue from list without server ownership, skip: " + sessionId)
            return
        }
        launchDrain(sessionId, serverId)
    }

    /** 面板单条「发送」按钮：立即发送指定条目（非队首也行，插队语义）。 */
    fun sendOneNow(sessionId: String, serverId: String, id: Long, text: String) {
        if (draining.putIfAbsent(sessionId, true) != null) return
        appScope.launch {
            _drainingSessions.update { it + sessionId }
            try {
                if (sendText(serverId, sessionId, text)) {
                    pendingMessageRepository.delete(id)
                }
            } finally {
                draining.remove(sessionId)
                _drainingSessions.update { it - sessionId }
            }
        }
    }

    private fun launchDrain(sessionId: String, serverId: String) {
        if (draining.putIfAbsent(sessionId, true) != null) {
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                AppLogger.d(TAG, "drain already in-flight, skip: " + sessionId)
            }
            return
        }
        appScope.launch {
            _drainingSessions.update { it + sessionId }
            try {
                val head = pendingMessageRepository.peekHead(sessionId) ?: return@launch
                if (sendText(serverId, sessionId, head.text)) {
                    pendingMessageRepository.delete(head.id)
                }
                // 失败：留在队首，T1 心跳 5s 无限重试（用户定案 2026-08-21）
            } finally {
                draining.remove(sessionId)
                _drainingSessions.update { it - sessionId }
            }
        }
    }

    private suspend fun sendText(serverId: String, sessionId: String, text: String): Boolean {
        return try {
            sendMessageUseCaseProvider.get().sendPrompt(
                serverId = serverId,
                sessionId = sessionId,
                parts = listOf(PromptPart(type = "text", text = text)),
                model = null,
                agent = "",
                variant = null,
                directory = null,
            )
            sessionStateService.onClientSendParts(sessionId)
            AppLogger.i(TAG, "queued message sent: " + sessionId + " (" + text.length + " chars)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "queued message send failed: " + sessionId + " - " + e.message, e)
            false
        }
    }

    private companion object {
        /** T1 状态补偿心跳周期（每拍只做一次轻量 DISTINCT 查询 + 逐会话门槛检查）。 */
        const val COMPENSATION_HEARTBEAT_MS = 5_000L
    }
}
