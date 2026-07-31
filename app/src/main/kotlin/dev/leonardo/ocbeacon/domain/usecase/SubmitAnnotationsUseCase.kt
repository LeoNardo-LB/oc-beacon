package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.model.AnnotationPromptBuilder
import javax.inject.Inject

/**
 * Submit annotations as a structured prompt to the session's AI.
 * Builds prompt via [AnnotationPromptBuilder], wraps in [PromptPart],
 * sends via [ChatRepository.promptAsync].
 */
class SubmitAnnotationsUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(
        serverId: String,
        sessionId: String,
        annotations: List<Annotation>,
        overallNote: String,
        filePath: String,
        directory: String
    ): Result<Unit> {
        val promptText = AnnotationPromptBuilder.build(annotations, overallNote, filePath, directory)
        return chatRepository.promptAsync(
            serverId = serverId, sessionId = sessionId,
            parts = listOf(PromptPart(type = "text", text = promptText)),
            model = null, agent = null, variant = null, directory = directory
        )
    }
}
