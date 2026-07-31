package dev.leonardo.octether.ui.screens.chat.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/** Result of parsing a Part.Question question field. */
internal data class ParsedQuestion(
    val displayText: String,
    val answers: List<String>,
    val rawExtra: String
)

internal data class QHistOption(val label: String, val description: String = "")

internal data class QHistItem(
    val text: String,
    val options: List<QHistOption>,
    val answers: List<String>,
    val isMultiple: Boolean = false
)

/** Pure-logic question field parsing — extracted from PartContent.kt. */
internal object QuestionParser {

    /** Parse question field — handles plain text, JSON, and opencode text format. */
    fun parseQuestionContent(raw: String): ParsedQuestion {
        val trimmed = raw.trim()

        // Format 1: opencode text ("questions: [...]\nUser has answered: ...")
        if (trimmed.contains("questions:") || trimmed.contains("User has answered")) {
            val questionText = Regex("\"question\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.getOrNull(1)
                ?: trimmed.lines().firstOrNull { it.isNotBlank() && !it.startsWith("Asked") }
                ?: trimmed
            val answers = mutableListOf<String>()
            val answerSection = trimmed.substringAfter("User has answered", "")
            if (answerSection.isNotBlank()) {
                val quoted = Regex("\"([^\"]+)\"").findAll(answerSection).map { it.groupValues[1] }.toList()
                if (quoted.isNotEmpty()) answers.addAll(quoted)
                else {
                    val plain = answerSection.removePrefix(":").removePrefix(" your questions:").trim()
                    if (plain.isNotBlank()) answers.add(plain)
                }
            }
            return ParsedQuestion(displayText = questionText, answers = answers, rawExtra = "")
        }

        // Format 2: pure JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return try {
                val json = JSONObject(trimmed)
                val q = json.optString("question", raw)
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
                ParsedQuestion(displayText = q, answers = answers, rawExtra = "")
            } catch (e: Exception) {
                ParsedQuestion(displayText = raw, answers = emptyList(), rawExtra = "")
            }
        }

        // Format 3: plain text
        return ParsedQuestion(displayText = raw, answers = emptyList(), rawExtra = "")
    }

    /**
     * Parse question data from tool input (has full options) and output (has user answers).
     * Input format: {"questions": [{"question": "...", "options": [{"label":"A",...}]}]}
     * Output format: "questions: [...]\nUser has answered: \"answer\""
     */
    fun parseQuestionFromToolData(
        id: String,
        input: Map<String, JsonElement>,
        output: String
    ): List<QHistItem> {
        val items = mutableListOf<QHistItem>()

        // 1. Extract ALL options from tool input (structured JSON with options array)
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
                items.add(QHistItem(qText, opts, emptyList()))
            }
        }

        // Fallback: parse from output if input has no questions
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
                    items.add(QHistItem(qText, opts, emptyList()))
                }
            } catch (e: Exception) {
                items.add(QHistItem(output.lines().firstOrNull { it.isNotBlank() } ?: "", emptyList(), emptyList()))
            }
        }

        // 2. Extract user answers from output format: "question text"="answer1, answer2"
        val answerSection = output.substringAfter("User has answered", "")
            .substringBefore(". You can")
        val answerPairs = Regex("\"([^\"]+)\"=\"([^\"]+)\"").findAll(answerSection).toList()
        answerPairs.forEachIndexed { idx, match ->
            val answers = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (idx < items.size) {
                items[idx] = items[idx].copy(answers = answers)
            }
        }
        // Fallback: if no "q"="a" pairs, try plain answers after last = sign
        if (answerPairs.isEmpty() && items.isNotEmpty()) {
            val afterEquals = answerSection.substringAfter("=", "").trim().trim('"')
            val fallbackAnswers = afterEquals.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (fallbackAnswers.isNotEmpty()) items[0] = items[0].copy(answers = fallbackAnswers)
        }

        return items
    }
}
