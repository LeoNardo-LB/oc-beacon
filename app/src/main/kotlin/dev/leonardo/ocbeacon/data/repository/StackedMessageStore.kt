package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.StackedMessage
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import dev.leonardo.ocbeacon.logging.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val TAG = "StackedMsgStore"
private val STACKED_KEY = stringPreferencesKey("stacked_messages_v1")

/**
 * #348 堆积消息仓库（本地排队，2026-09-07 定案重建）。
 *
 * 语义复刻 2026-08-20 设计（ce8cbc1e + #176/#177 状态补偿）：
 * - busy 时用户「堆积消息」→ 本地按序保存；**每次自然 turn 结束发队首 1 条**，
 *   该 turn 自然结束自动推进下一条；
 * - 发送 = peek → POST → 成功才删（at-least-once）；失败留队首，心跳 5s 重试；
 * - 模型/agent/variant 不快照（会话/服务器默认）——与 ChatSendDelegate 主链
 *   的唯一差异即无 UI 配置携带；
 * - 护栏：非 Idle / 待处理问题权限（hasPendingUserInput）/ 服务器归属未知 → 跳过。
 *
 * 与旧 PendingMessagePipeline 的差异（#289 拆除后精益重建）：
 * - 持久化 = 共享 Preferences DataStore（JSON 镜像）取代 Room 表——跨进程
 *   重启存活，无迁移面；写放大可忽略（堆积量个位数）；
 * - 触发器三合一内聚：T3 statusFlow Idle 转移 / T2 入队即查 / T1 心跳补偿；
 * - UI 呈现 = composer 上方 chips 条（#348 定案）取代 FAB STACKED sheet。
 */
@Singleton
class StackedMessageStore @Inject constructor(
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val dataStore: DataStore<Preferences>,
    private val sessionStateRepository: SessionStateRepository,
    // Provider 打破 仓库族 循环（SendMessageUseCase → ChatRepository → EventDispatcher 链）
    private val sendMessageUseCaseProvider: Provider<SendMessageUseCase>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _stackedBySession = MutableStateFlow<Map<String, List<StackedMessage>>>(emptyMap())

    /** sessionId → 堆积消息（时间序）。 */
    val stackedBySession: StateFlow<Map<String, List<StackedMessage>>> = _stackedBySession

    /** 正在发送堆积消息的会话集合（UI「发送中」锁：编辑/删除禁用）。 */
    private val _drainingSessions = MutableStateFlow<Set<String>>(emptySet())
    val drainingSessions: StateFlow<Set<String>> = _drainingSessions

    private val draining = ConcurrentHashMap<String, Boolean>()

    /** T1 心跳 Job（测试门取消用）。 */
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    /**
     * 测试门：取消 T1 心跳——无限 delay 循环与虚拟时钟 advanceUntilIdle
     * 不相容（时钟无限推进 → 任务队列无限膨胀）。T2/T3 事件驱动不受影响。
     */
    @androidx.annotation.VisibleForTesting
    internal fun disableCompensationHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    init {
        // 0) 持久化载入（进程重启恢复）
        appScope.launch {
            try {
                val raw = dataStore.data.first()[STACKED_KEY]
                if (!raw.isNullOrBlank()) {
                    val list = runCatching { json.decodeFromString<List<StackedMessage>>(raw) }.getOrDefault(emptyList())
                    _stackedBySession.value = list.groupBy { it.sessionId }
                    if (list.isNotEmpty()) {
                        AppLogger.i(TAG, "restored " + list.size + " stacked message(s) across " + _stackedBySession.value.size + " session(s)")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "restore stacked messages failed: " + e.message)
            }
        }
        // T3：任意来源落 Idle（转移，含首见）→ drain（自然结束/断连恢复补态）
        appScope.launch {
            var prev: Map<String, SessionStatus> = emptyMap()
            sessionStateRepository.statusFlow.collect { states ->
                states.forEach { (sessionId, status) ->
                    val was = prev[sessionId]
                    if (status is SessionStatus.Idle && was == null || (was != null && was !is SessionStatus.Idle)) {
                        drainIfIdle(sessionId)
                    }
                }
                prev = states
            }
        }
        // T1：心跳补偿（POST 失败无限重试源；每拍扫有堆积会话）——Job 可取消
        //（单测门：无限 delay 循环与虚拟时钟 advanceUntilIdle 不相容，见 disable 函数）
        heartbeatJob = appScope.launch {
            while (isActive) {
                delay(COMPENSATION_HEARTBEAT_MS)
                _stackedBySession.value.keys.forEach { drainIfIdle(it) }
            }
        }
    }

    /** 入队（UI 层已执行附件置灰门控——堆积仅纯文本）。 */
    fun enqueue(serverId: String, sessionId: String, text: String) {
        if (text.isBlank()) return
        val msg = StackedMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            serverId = serverId,
            text = text,
            createdAt = System.currentTimeMillis(),
        )
        _stackedBySession.update { all ->
            all + (sessionId to ((all[sessionId] ?: emptyList()) + msg))
        }
        persist()
        // T2：入队即查（turn 已在入队前结束的场景——#176）
        drainIfIdle(sessionId)
    }

    fun remove(sessionId: String, id: String) {
        _stackedBySession.update { all ->
            val rest = (all[sessionId] ?: emptyList()).filterNot { it.id == id }
            if (rest.isEmpty()) all - sessionId else all + (sessionId to rest)
        }
        persist()
    }

    fun updateText(sessionId: String, id: String, text: String) {
        _stackedBySession.update { all ->
            val updated = (all[sessionId] ?: emptyList()).map { if (it.id == id) it.copy(text = text) else it }
            all + (sessionId to updated)
        }
        persist()
    }

    fun clear(sessionId: String) {
        _stackedBySession.update { it - sessionId }
        persist()
    }

    /** 会话删除级联（EventDispatcher SessionDeleted 分发点调用）。 */
    fun onSessionDeleted(sessionId: String) = clear(sessionId)

    /** 手动放行队首 1 条（chips「立即发送」——无 Idle 门槛）。 */
    fun sendOneNow(sessionId: String) {
        val serverId = sessionStateRepository.serverIdFor(sessionId) ?: return
        launchDrain(sessionId, serverId)
    }

    private fun drainIfIdle(sessionId: String) {
        val status = sessionStateRepository.statusFlow.value[sessionId]
        if (status !is SessionStatus.Idle) return
        if (sessionStateRepository.hasPendingUserInput(sessionId)) return
        val serverId = sessionStateRepository.serverIdFor(sessionId) ?: return
        launchDrain(sessionId, serverId)
    }

    private fun launchDrain(sessionId: String, serverId: String) {
        if (draining.putIfAbsent(sessionId, true) != null) return
        appScope.launch {
            _drainingSessions.update { it + sessionId }
            try {
                val head = _stackedBySession.value[sessionId]?.firstOrNull() ?: return@launch
                if (sendText(serverId, sessionId, head.text)) {
                    remove(sessionId, head.id)
                }
                // 失败：留队首，T1 心跳 5s 重试
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
            sessionStateRepository.onClientSendParts(sessionId)
            AppLogger.i(TAG, "stacked message sent: " + sessionId + " (" + text.length + " chars)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "stacked message send failed: " + sessionId + " - " + e.message, e)
            false
        }
    }

    private fun persist() {
        appScope.launch {
            try {
                val flat = _stackedBySession.value.values.flatten()
                dataStore.edit { prefs ->
                    if (flat.isEmpty()) prefs.remove(STACKED_KEY)
                    else prefs[STACKED_KEY] = json.encodeToString(flat)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "persist stacked messages failed: " + e.message)
            }
        }
    }

    private companion object {
        const val COMPENSATION_HEARTBEAT_MS = 5_000L
    }
}
