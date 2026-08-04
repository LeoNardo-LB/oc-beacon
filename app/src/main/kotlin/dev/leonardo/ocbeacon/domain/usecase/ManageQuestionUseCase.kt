package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use Case：管理问题（观察 + 回复）。
 * 供 Phase 2 ChatViewModel 使用。
 */
class ManageQuestionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        chatRepository.getQuestionsFlow(sessionId)

    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> =
        chatRepository.replyQuestion(questionId, answer)
}
