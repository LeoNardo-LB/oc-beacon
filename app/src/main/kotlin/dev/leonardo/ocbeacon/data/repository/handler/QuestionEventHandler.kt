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

    fun clearForSession(sessionId: String) {
        _questions.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _questions.update { it - sessionIds }
    }

    fun clearAll() {
        _questions.value = emptyMap()
    }

    /**
     * 获取某会话的所有待处理问题，包括来自子会话的问题。
     * 这使父会话 UI 能显示子代理的问题请求。
     * 子会话问题用 [SseEvent.QuestionAsked.sourceSessionTitle] 标注。
     */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.QuestionAsked> {
        val currentQuestions = _questions.value[sessionId] ?: emptyList()

        // 查找子会话（parentId == sessionId 的会话）
        val childSessionIds = sessions
            .filter { it.parentId == sessionId }
            .map { it.id }
            .toSet()

        // 聚合所有子会话的问题，并用来源标题标注
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
