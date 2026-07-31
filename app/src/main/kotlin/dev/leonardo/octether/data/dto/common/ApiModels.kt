package dev.leonardo.octether.data.dto.common

import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelSelection(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String
)

@Serializable
data class OutputFormat(
    val type: String,
    val schema: String? = null
)

open class PtySocket(
    private val session: ClientWebSocketSession
) {
    open suspend fun send(input: String) {
        session.send(input)
    }

    open suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
    }

    open suspend fun readLoop(onText: suspend (String) -> Unit) {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> onText(frame.readText())
                is Frame.Binary -> {
                    val data = frame.data
                    if (data.isNotEmpty() && data[0].toInt() == 0) continue
                    onText(data.toString(Charsets.UTF_8))
                }
                else -> { /* ignore */ }
            }
        }
    }
}
