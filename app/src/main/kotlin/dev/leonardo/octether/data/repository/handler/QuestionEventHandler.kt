package dev.leonardo.octether.data.repository.handler

import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles question events: asked, replied, rejected.
 * Manages: questions
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
                current // already exists, skip duplicate
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
     * Get all pending questions for a session, including questions from child sessions.
     * This enables the parent session UI to display sub-agent question requests.
     * Child session questions are annotated with [SseEvent.QuestionAsked.sourceSessionTitle].
     */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.QuestionAsked> {
        val currentQuestions = _questions.value[sessionId] ?: emptyList()

        // Find child sessions (sessions whose parentId == sessionId)
        val childSessionIds = sessions
            .filter { it.parentId == sessionId }
            .map { it.id }
            .toSet()

        // Aggregate questions from all child sessions, annotating with source title
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
