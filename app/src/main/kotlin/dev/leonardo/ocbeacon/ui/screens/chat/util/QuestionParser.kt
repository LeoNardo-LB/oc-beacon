package dev.leonardo.ocbeacon.ui.screens.chat.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/** 解析 Part.Question 的 question 字段的结果。 */
internal data class ParsedQuestion(
    val displayText: String,
    val answers: List<String>,
    val rawExtra: String,
    val isMultiple: Boolean = false
)

internal data class QHistOption(val label: String, val description: String = "")

internal data class QHistItem(
    val text: String,
    val options: List<QHistOption>,
    val answers: List<String>,
    val isMultiple: Boolean = false
)

/** 纯逻辑的 question 字段解析 —— 从 PartContent.kt 抽取。 */
internal object QuestionParser {

        // #106-4：解析正则预编译（原每条 question 渲染现场编译）
    private val QUESTION_FIELD_REGEX = Regex("\"question\"\\s*:\\s*\"([^\"]+)\"")
    private val QUOTED_STRING_REGEX = Regex("\"([^\"]+)\"")
    private val ANSWER_PAIR_REGEX = Regex("\"([^\"]+)\"=\"([^\"]+)\"")

    /** 解析 question 字段 —— 处理纯文本、JSON 和 opencode 文本格式。 */
    fun parseQuestionContent(raw: String): ParsedQuestion {
        val trimmed = raw.trim()

        // 格式 1：opencode 文本（"questions: [...]\nUser has answered: ..."）
        if (trimmed.contains("questions:") || trimmed.contains("User has answered")) {
            val questionText = QUESTION_FIELD_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
                ?: trimmed.lines().firstOrNull { it.isNotBlank() && !it.startsWith("Asked") }
                ?: trimmed
            val answers = mutableListOf<String>()
            val answerSection = trimmed.substringAfter("User has answered", "")
            if (answerSection.isNotBlank()) {
                val quoted = QUOTED_STRING_REGEX.findAll(answerSection).map { it.groupValues[1] }.toList()
                if (quoted.isNotEmpty()) answers.addAll(quoted)
                else {
                    val plain = answerSection.removePrefix(":").removePrefix(" your questions:").trim()
                    if (plain.isNotBlank()) answers.add(plain)
                }
            }
            return ParsedQuestion(displayText = questionText, answers = answers, rawExtra = "")
        }

        // 格式 2：纯 JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return try {
                val json = JSONObject(trimmed)
                val q = json.optString("question", raw)
                val isMultiple = json.optBoolean("multiple", false)
                val answers = mutableListOf<String>()
                json.optString("answer", "").takeIf { it.isNotBlank() }?.let { answers.add(it) }
                json.optJSONArray("answers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.get(i)
                        if (item is String) answers.add(item)
                        else if (item is JSONArray) {
                            for (j in 0 until item.length()) answers.add(item.getString(j))
                        }
                    }
                }
                ParsedQuestion(displayText = q, answers = answers, rawExtra = "", isMultiple = isMultiple)
            } catch (e: Exception) {
                ParsedQuestion(displayText = raw, answers = emptyList(), rawExtra = "")
            }
        }

        // 格式 3：纯文本
        return ParsedQuestion(displayText = raw, answers = emptyList(), rawExtra = "")
    }

    /**
     * 从工具输入（含完整选项）和输出（含用户答案）解析问题数据。
     * 输入格式：{"questions": [{"question": "...", "options": [{"label":"A",...}]}]}
     * 输出格式："questions: [...]\nUser has answered: \"answer\""
     */
    fun parseQuestionFromToolData(
        id: String,
        input: Map<String, JsonElement>,
        output: String
    ): List<QHistItem> {
        val items = mutableListOf<QHistItem>()

        // 1. 从工具输入提取全部选项（带 options 数组的结构化 JSON）
        val questionsElement = input.entries
            .firstOrNull { it.key.contains("question", ignoreCase = true) }
            ?.value
        if (questionsElement is JsonArray) {
            questionsElement.forEach { qEl ->
                val qObj = qEl.jsonObject
                val qText = qObj["question"]?.jsonPrimitive?.content ?: ""
                val optsArr = qObj["options"]?.jsonArray
                val opts = optsArr?.map { optEl ->
                    val optObj = optEl.jsonObject
                    QHistOption(
                        label = optObj["label"]?.jsonPrimitive?.content ?: optObj["value"]?.jsonPrimitive?.content ?: "",
                        description = optObj["description"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
                val multiple = qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false
                items.add(QHistItem(qText, opts, emptyList(), isMultiple = multiple))
            }
        }

        // 回退：输入无问题时从输出解析
        if (items.isEmpty()) {
            val qSection = output.substringAfter("questions:", "").trim()
            val jsonPart = qSection.substringBefore("\nUser has answered").substringBefore("\nAsked").trim()
            try {
                val arr = JSONArray(jsonPart)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val qText = obj.optString("question", "")
                    val optsArr = obj.optJSONArray("options")
                    val opts = mutableListOf<QHistOption>()
                    if (optsArr != null) {
                        for (j in 0 until optsArr.length()) {
                            val opt = optsArr.optJSONObject(j)
                            if (opt != null) opts.add(QHistOption(
                                opt.optString("label", opt.optString("value", "")),
                                opt.optString("description", "")
                            ))
                        }
                    }
                    items.add(QHistItem(qText, opts, emptyList(), isMultiple = obj.optBoolean("multiple", false)))
                }
            } catch (e: Exception) {
                items.add(QHistItem(output.lines().firstOrNull { it.isNotBlank() } ?: "", emptyList(), emptyList()))
            }
        }

        // 2. 从输出格式提取用户答案："question text"="answer1, answer2"
        val answerSection = output.substringAfter("User has answered", "")
            .substringBefore(". You can")
        val answerPairs = ANSWER_PAIR_REGEX.findAll(answerSection).toList()
        answerPairs.forEachIndexed { idx, match ->
            val answers = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (idx < items.size) {
                items[idx] = items[idx].copy(answers = answers)
            }
        }
        // 回退：若无 "q"="a" 对，尝试最后一个 = 号之后的纯答案
        if (answerPairs.isEmpty() && items.isNotEmpty()) {
            val afterEquals = answerSection.substringAfter("=", "").trim().trim('"')
            val fallbackAnswers = afterEquals.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (fallbackAnswers.isNotEmpty()) items[0] = items[0].copy(answers = fallbackAnswers)
        }

        return items
    }
}
