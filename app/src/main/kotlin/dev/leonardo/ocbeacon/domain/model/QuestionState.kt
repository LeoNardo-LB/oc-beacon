package dev.leonardo.ocbeacon.domain.model

data class QuestionState(
    val id: String,
    val sessionId: String,
    val questions: List<Question>,
    val tool: ToolRef? = null
) {
    data class Question(
        val header: String,
        val question: String,
        val multiple: Boolean = false,
        val custom: Boolean = true,
        val options: List<Option>
    )

    data class Option(
        val label: String,
        val description: String
    )
}
