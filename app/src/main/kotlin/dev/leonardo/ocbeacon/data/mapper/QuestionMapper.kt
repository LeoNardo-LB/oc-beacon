package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.QuestionInfo
import dev.leonardo.ocbeacon.data.dto.response.QuestionOption
import dev.leonardo.ocbeacon.data.dto.response.QuestionRequest
import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * 在 API DTO（QuestionRequest）与领域模型（SseEvent.QuestionAsked）之间映射。
 *
 * 关键差异：
 * - API 使用 QuestionInfo/QuestionOption；领域使用 QuestionAsked.Question/Option
 * - 字段名相同，但类型位于不同包中
 */
object QuestionMapper {

    /** API DTO → 领域模型 */
    fun toDomain(dto: QuestionRequest): SseEvent.QuestionAsked {
        return SseEvent.QuestionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            questions = dto.questions.map { it.toDomain() },
            tool = dto.tool
        )
    }

    /** 领域模型 → API DTO */
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
