package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.QuestionInfo
import dev.leonardo.ocbeacon.data.dto.response.QuestionOption
import dev.leonardo.ocbeacon.data.dto.response.QuestionRequest
import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * Maps between API DTO (QuestionRequest) and Domain (SseEvent.QuestionAsked).
 *
 * Key differences:
 * - API uses QuestionInfo/QuestionOption; Domain uses QuestionAsked.Question/Option
 * - Field names are identical but types are in different packages
 */
object QuestionMapper {

    /** API DTO → Domain */
    fun toDomain(dto: QuestionRequest): SseEvent.QuestionAsked {
        return SseEvent.QuestionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            questions = dto.questions.map { it.toDomain() },
            tool = dto.tool
        )
    }

    /** Domain → API DTO */
    fun toDto(domain: SseEvent.QuestionAsked): QuestionRequest {
        return QuestionRequest(
            id = domain.id,
            sessionId = domain.sessionId,
            questions = domain.questions.map { it.toDto() },
            tool = domain.tool
        )
    }

    private fun QuestionInfo.toDomain(): SseEvent.QuestionAsked.Question {
        return SseEvent.QuestionAsked.Question(
            header = header,
            question = question,
            multiple = multiple,
            custom = custom,
            options = options.map { SseEvent.QuestionAsked.Option(it.label, it.description) }
        )
    }

    private fun SseEvent.QuestionAsked.Question.toDto(): QuestionInfo {
        return QuestionInfo(
            question = question,
            header = header,
            options = options.map { QuestionOption(it.label, it.description) },
            multiple = multiple,
            custom = custom
        )
    }
}
