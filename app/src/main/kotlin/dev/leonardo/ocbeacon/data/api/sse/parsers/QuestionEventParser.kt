package dev.leonardo.ocbeacon.data.api.sse.parsers

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses question events:
 * - question.asked, question.replied, question.rejected
 */
class QuestionEventParser : SseEventParser {

    private val handledTypes = setOf("question.asked", "question.replied", "question.rejected")

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "question.asked" -> {
                    val id = props.str("id")
                    val sessionId = props.str("sessionID")
                    val toolRef = props["tool"]?.jsonObject?.let { toolObj ->
                        ToolRef(
                            messageId = toolObj.str("messageID"),
                            callId = toolObj.str("callID")
                        )
                    }
                    Log.i(TAG, "Question asked for session $sessionId")
                    val questionsArr = props["questions"]?.jsonArray
                    val questions = questionsArr?.map { qElement ->
                        val qObj = qElement.jsonObject
                        val optionsArr = qObj["options"]?.jsonArray ?: JsonArray(emptyList())
                        val options = optionsArr.map { oElement ->
                            val oObj = oElement.jsonObject
                            SseEvent.QuestionAsked.Option(
                                label = oObj.str("label"),
                                description = oObj.str("description")
                            )
                        }
                        SseEvent.QuestionAsked.Question(
                            header = qObj.str("header"),
                            question = qObj.str("question"),
                            multiple = qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false,
                            custom = qObj["custom"]?.jsonPrimitive?.booleanOrNull ?: true,
                            options = options
                        )
                    } ?: emptyList()
                    SseEvent.QuestionAsked(
                        id = id,
                        sessionId = sessionId,
                        questions = questions,
                        tool = toolRef
                    )
                }

                "question.replied" -> {
                    val sessionId = props.str("sessionID")
                    val requestId = props.str("requestID")
                    SseEvent.QuestionReplied(sessionId = sessionId, requestId = requestId)
                }

                "question.rejected" -> {
                    val sessionId = props.str("sessionID")
                    val requestId = props.str("requestID")
                    SseEvent.QuestionRejected(sessionId = sessionId, requestId = requestId)
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }
}
