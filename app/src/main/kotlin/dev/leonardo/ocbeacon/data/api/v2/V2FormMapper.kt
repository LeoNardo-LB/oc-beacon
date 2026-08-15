package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.data.dto.response.QuestionInfo
import dev.leonardo.ocbeacon.data.dto.response.QuestionOption
import dev.leonardo.ocbeacon.data.dto.response.QuestionRequest
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V2 form 服务 → 领域模型映射层（纯函数）。
 *
 * #130 背景（2026-08-14 官方回复 + 真机抓帧确认）：V2 的 question 工具由
 * form 服务驱动——服务器发 `form.created` SSE（metadata.kind == "question"），
 * App 回复 `POST /api/session/{id}/form/{formID}/reply`。旧 question.asked /
 * /api/question/request 是 stale surface（未来移除）。
 *
 * 本类职责：
 * 1. [map]：SSE `form.created/replied/cancelled` → 领域事件
 *    （form.created 仅 kind=question 映射为 QuestionAsked；其他 kind 返回 null 忽略）
 * 2. [toQuestionRequest]：REST `GET /api/form/request` 的 form JSON → QuestionRequest DTO
 * 3. [buildAnswerBody]：UI 提交的 label 答案 → form reply 的 answer map（label→value）
 *
 * 实测 form 结构（next-17430）：
 * ```
 * {"id":"frm_...","sessionID":"ses_...","title":"Questions",
 *  "metadata":{"kind":"question","tool":{"messageID":"msg_...","id":"call_..."}},
 *  "fields":[{"key":"q0","title":"...","description":"...","type":"string",
 *             "options":[{"value":"...","label":"...","description":"..."}],"custom":true},
 *            {"key":"q1","title":"...","type":"multiselect","options":[...]}]}
 * ```
 *
 * field.type：string（单选）/ multiselect（多选）/ number / integer / boolean / external。
 * 单选 value 是标量字符串，多选是字符串数组（Form.Value = string|number|boolean|string[]）。
 */
object V2FormMapper {

    private const val KIND_QUESTION = "question"

    // ============ SSE 事件映射 ============

    /**
     * 尝试将 V2 form 事件映射为领域 SseEvent。
     * - form.created（kind=question）→ SseEvent.QuestionAsked
     * - form.replied → SseEvent.QuestionReplied（复用现有卡片移除路径）
     * - form.cancelled → SseEvent.QuestionRejected（复用现有卡片移除路径）
     * 其他类型 / 非 question kind → null。
     */
    fun map(type: String, props: JsonObject): SseEvent? = when (type) {
        "form.created" -> {
            val form = props["form"]?.jsonObject ?: return null
            parseCreated(form)
        }
        "form.replied" -> {
            val id = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.QuestionReplied(sessionId = sessionId, requestId = id)
        }
        "form.cancelled" -> {
            val id = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.QuestionRejected(sessionId = sessionId, requestId = id)
        }
        // 2026-08-15（research/09 P0）：V2 主干已用 question.v2.* 取代 form.*
        //（next-17430 为中间契约，两代并存）。payload = Request 字段平铺：
        // {id, sessionID, questions:[{question,header,options,multiple?,custom?}], tool?}
        // ——与领域 Question 结构同构（label/description 一致），直接映射。
        "question.v2.asked" -> parseQuestionV2(props)
        "question.v2.replied" -> {
            val id = props["requestID"]?.jsonPrimitive?.contentOrNull
                ?: props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.QuestionReplied(sessionId = sessionId, requestId = id)
        }
        "question.v2.rejected" -> {
            val id = props["requestID"]?.jsonPrimitive?.contentOrNull
                ?: props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.QuestionRejected(sessionId = sessionId, requestId = id)
        }
        else -> null
    }

    /** question.v2.asked payload（Request 平铺）→ QuestionAsked。 */
    private fun parseQuestionV2(props: JsonObject): SseEvent.QuestionAsked? {
        val id = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
        val questions = (props["questions"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { el ->
                (el as? JsonObject)?.let { q ->
                    val options = (q["options"]?.jsonArray ?: JsonArray(emptyList()))
                        .mapNotNull { o -> (o as? JsonObject)?.toOption() }
                    SseEvent.QuestionAsked.Question(
                        header = q["header"]?.jsonPrimitive?.contentOrNull ?: "",
                        question = q["question"]?.jsonPrimitive?.contentOrNull ?: "",
                        multiple = q["multiple"]?.jsonPrimitive?.booleanOrNull ?: false,
                        custom = q["custom"]?.jsonPrimitive?.booleanOrNull ?: true,
                        options = options,
                        key = null
                    )
                }
            }
        if (questions.isEmpty()) return null
        return SseEvent.QuestionAsked(
            id = id,
            sessionId = sessionId,
            questions = questions,
            tool = (props["tool"] as? JsonObject)?.toToolRef()
        )
    }

    /** form.created 的 form 对象 → QuestionAsked（仅 kind=question）。 */
    private fun parseCreated(form: JsonObject): SseEvent.QuestionAsked? {
        val metadata = form["metadata"]?.jsonObject ?: return null
        if (metadata["kind"]?.jsonPrimitive?.contentOrNull != KIND_QUESTION) return null
        val id = form["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = form["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
        val fields = form["fields"]?.jsonArray ?: return null
        val questions = fields.mapNotNull { it.jsonObject?.toQuestion() }
        if (questions.isEmpty()) return null
        return SseEvent.QuestionAsked(
            id = id,
            sessionId = sessionId,
            questions = questions,
            tool = metadata["tool"]?.jsonObject?.toToolRef()
        )
    }

    /** metadata.tool（{messageID, id}）→ ToolRef（messageId, callId）。V2 用 id 而非 callID。 */
    private fun JsonObject.toToolRef(): ToolRef? {
        val messageId = this["messageID"]?.jsonPrimitive?.contentOrNull ?: return null
        val callId = this["id"]?.jsonPrimitive?.contentOrNull
            ?: this["callID"]?.jsonPrimitive?.contentOrNull
            ?: ""
        return ToolRef(messageId = messageId, callId = callId)
    }

    /** form field → 领域 Question（V1 兼容字段 + V2 key）。 */
    private fun JsonObject.toQuestion(): SseEvent.QuestionAsked.Question {
        val type = this["type"]?.jsonPrimitive?.contentOrNull
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val description = this["description"]?.jsonPrimitive?.contentOrNull
        val multiple = type == "multiselect"
        val custom = this["custom"]?.jsonPrimitive?.booleanOrNull ?: true
        val options = (this["options"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonObject?.toOption() }
        return SseEvent.QuestionAsked.Question(
            header = title,
            // V1 语义对照：question=完整问题文本（V1 实测 question="问题1：..."，
            // header="今天吃什么"）；form 的 description 即完整问题，title 即短 header。
            question = description?.takeIf { it.isNotBlank() } ?: title,
            multiple = multiple,
            custom = custom,
            options = options,
            key = this["key"]?.jsonPrimitive?.contentOrNull
        )
    }

    /** form option（{value, label, description}）→ 领域 Option（label, description, value）。 */
    private fun JsonObject.toOption(): SseEvent.QuestionAsked.Option = SseEvent.QuestionAsked.Option(
        label = this["label"]?.jsonPrimitive?.contentOrNull ?: "",
        description = this["description"]?.jsonPrimitive?.contentOrNull ?: "",
        value = this["value"]?.jsonPrimitive?.contentOrNull
    )

    // ============ REST 轮询映射 ============

    /**
     * `GET /api/form/request` 返回的 form JSON → QuestionRequest DTO。
     * 仅 kind=question 映射；其他 kind 返回 null（调用方过滤）。
     */
    fun toQuestionRequest(form: JsonObject): QuestionRequest? {
        val metadata = form["metadata"]?.jsonObject ?: return null
        if (metadata["kind"]?.jsonPrimitive?.contentOrNull != KIND_QUESTION) return null
        val id = form["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = form["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
        val fields = form["fields"]?.jsonArray ?: return null
        val questions = fields.mapNotNull { it.jsonObject?.toQuestionInfo() }
        if (questions.isEmpty()) return null
        return QuestionRequest(
            id = id,
            sessionId = sessionId,
            questions = questions,
            tool = metadata["tool"]?.jsonObject?.toToolRef()
        )
    }

    /** form field → QuestionInfo DTO（含 key/value）。 */
    private fun JsonObject.toQuestionInfo(): QuestionInfo {
        val type = this["type"]?.jsonPrimitive?.contentOrNull
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val description = this["description"]?.jsonPrimitive?.contentOrNull
        return QuestionInfo(
            question = description?.takeIf { it.isNotBlank() } ?: title,
            header = title,
            options = (this["options"]?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { it.jsonObject?.toQuestionOption() },
            multiple = type == "multiselect",
            custom = this["custom"]?.jsonPrimitive?.booleanOrNull ?: true,
            key = this["key"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun JsonObject.toQuestionOption(): QuestionOption = QuestionOption(
        label = this["label"]?.jsonPrimitive?.contentOrNull ?: "",
        description = this["description"]?.jsonPrimitive?.contentOrNull ?: "",
        value = this["value"]?.jsonPrimitive?.contentOrNull
    )

    // ============ reply body 构造 ============

    /**
     * 构造 form reply 的 answer body：
     * `{"answer": {"q0": "米饭", "q1": ["Kotlin", "Rust"]}}`
     *
     * @param answers UI 提交的答案（每题的 label 列表，按 questions 顺序）
     * @param questions 领域 Question 列表（含 key + options 的 value/label）
     *   —— label→value 映射：匹配 option.label 的用 option.value；自定义输入（无匹配）用原文。
     */
    fun buildAnswerBody(
        answers: List<List<String>>,
        questions: List<SseEvent.QuestionAsked.Question>
    ): JsonObject {
        val answerMap = buildJsonAnswerMap(answers, questions)
        return JsonObject(mapOf("answer" to answerMap))
    }

    /** 纯 answer map（key → 单值或数组）。单选标量、多选数组；无 key 的题目跳过。 */
    fun buildJsonAnswerMap(
        answers: List<List<String>>,
        questions: List<SseEvent.QuestionAsked.Question>
    ): JsonObject {
        val fields = mutableMapOf<String, JsonElement>()
        questions.forEachIndexed { index, q ->
            val key = q.key ?: return@forEachIndexed
            val labels = answers.getOrNull(index).orEmpty()
            if (labels.isEmpty()) return@forEachIndexed
            // label → value 映射（option.label 匹配；否则原文=自定义输入）
            val valueByLabel = q.options.associate { it.label to (it.value ?: it.label) }
            val values = labels.map { valueByLabel[it] ?: it }
            fields[key] = if (q.multiple) {
                JsonArray(values.map { JsonPrimitive(it) })
            } else {
                JsonPrimitive(values.firstOrNull() ?: "")
            }
        }
        return JsonObject(fields)
    }
}
