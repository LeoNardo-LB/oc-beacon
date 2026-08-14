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
        val options: List<Option>,
        /** V2 form field key（q0/q1...）；V1 为 null。 */
        val key: String? = null
    )

    data class Option(
        val label: String,
        val description: String,
        /** V2 form option value（提交用）；V1 为 null。 */
        val value: String? = null
    )
}
