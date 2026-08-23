package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理问题事件：asked、replied、rejected。
 * 管理：questions
 */
@Singleton
class QuestionEventHandler @Inject constructor() : SseEventHandler {

    private val _questions = MutableStateFlow<Map<String, List<SseEvent.QuestionAsked>>>(emptyMap())
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> = _questions.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.QuestionAsked -> { handleQuestionAsked(event); true }
            is SseEvent.QuestionReplied -> { handleQuestionReplied(event); true }
            is SseEvent.QuestionRejected -> { handleQuestionRejected(event); true }
            else -> false
        }
    }

    private fun handleQuestionAsked(event: SseEvent.QuestionAsked) {
        _questions.update { current ->
            val sessionQs = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            if (sessionQs.any { it.id == event.id }) {
                current // 已存在，跳过重复
            } else {
                sessionQs.add(event)
                current + (event.sessionId to sessionQs)
            }
        }
    }

    private fun handleQuestionReplied(event: SseEvent.QuestionReplied) {
        _questions.update { current ->
            val sessionQs = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionQs != null) current + (event.sessionId to sessionQs) else current
        }
    }

    private fun handleQuestionRejected(event: SseEvent.QuestionRejected) {
        _questions.update { current ->
            val sessionQs = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionQs != null) current + (event.sessionId to sessionQs) else current
        }
    }

    fun removeQuestion(questionId: String) {
        _questions.update { current ->
            current.mapValues { (_, qs) -> qs.filter { it.id != questionId } }
        }
    }

    fun setQuestions(sessionId: String, qs: List<SseEvent.QuestionAsked>) {
        _questions.update { current ->
            if (qs.isEmpty()) current - sessionId else current + (sessionId to qs)
        }
    }

    /**
     * 用 REST /question 轮询数据合并补全（2026-08-14 修复：V1 SSE 的
     * question.asked 事件可能不含 tool 字段 → QuestionAsked.tool 为 null →
     * 提问卡片无法嵌入触发消息气泡（降级为独立卡片）。REST 响应含
     * tool.messageID，轮询时按 id 合并补全。
     * 语义：REST 有而 SSE 无的条目 → 添加；SSE 已有但 tool 为空且 REST 带 tool
     * → 补全 tool；其余保留 SSE 数据（含 sourceSessionTitle 等瞬态字段）。
     */
    fun mergeFromREST(sessionId: String, qs: List<SseEvent.QuestionAsked>) {
        _questions.update { current ->
            val existing = current[sessionId]?.associateBy { it.id } ?: emptyMap()
            val restById = qs.associateBy { it.id }
            // 并集语义：REST 有而 SSE 无 → 添加；SSE 已有 → 保留 SSE
            // （仅当 SSE tool 为空且 REST 带 tool 时补全）。REST 缺失的
            // SSE 条目保留（轮询延迟窗口内不闪失），由 SSE 事件驱动删除。
            val merged = (existing.keys + restById.keys).mapNotNull { id ->
                val sseQ = existing[id]
                val restQ = restById[id]
                when {
                    sseQ == null -> restQ
                    sseQ.tool == null && restQ?.tool != null -> sseQ.copy(tool = restQ.tool)
                    else -> sseQ
                }
            }
            if (merged.isEmpty()) current - sessionId else current + (sessionId to merged)
        }
    }

    fun clearForSession(sessionId: String) {
        // 仅由 SessionDeleted 级联调用（服务器确认会话已删除时清理）。
        // 会话退出（releaseSessionData）不调用——pending questions 是服务器状态，
        // 退出后仍应显示 Asking（2026-08-14 修复返回列表状态闪烁）。
        _questions.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _questions.update { it - sessionIds }
    }

    fun clearAll() {
        _questions.value = emptyMap()
    }

    /**
     * 获取某会话的所有待处理问题，包括来自子智能体会话的问题。
     * 这使父会话 UI 能显示子代理的问题请求。
     * 子智能体会话问题用 [SseEvent.QuestionAsked.sourceSessionTitle] 标注。
     */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.QuestionAsked> {
        val currentQuestions = _questions.value[sessionId] ?: emptyList()

        // 查找子智能体会话（parentId == sessionId 的会话）
        val childSessionIds = sessions
            .filter { it.parentId == sessionId }
            .map { it.id }
            .toSet()

        // 聚合所有子智能体会话的问题，并用来源标题标注
        val childQuestions = _questions.value
            .filterKeys { it in childSessionIds }
            .entries
            .flatMap { (childId, qs) ->
                val childTitle = sessions.find { it.id == childId }?.title
                qs.map { q ->
                    q.copy(sourceSessionTitle = childTitle)
                }
            }

        return currentQuestions + childQuestions
    }
}
