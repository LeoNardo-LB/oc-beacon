package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.QuestionState
import dev.leonardo.octether.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// TODO: Add filtering/transformation logic or consider removing this UseCase if it remains a pure delegate
class QuestionHandlerUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun observeQuestions(sessionId: String): Flow<List<QuestionState>> =
        chatRepository.getQuestionsFlow(sessionId)
}
