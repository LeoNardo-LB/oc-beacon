package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.filterRenderableParts

import androidx.compose.runtime.Immutable

/**
 * Pre-computed rendering data for one display item (one assistant turn bubble).
 *
 * All filtering, grouping, and metadata extraction happen in [computeRenderableTurn],
 * so the Composable only iterates [renderItems] — zero computation during composition.
 *
 * Marked @Immutable so Compose can skip recomposition for items whose RenderableTurn
 * hasn't changed (e.g., during pagination, existing items' data is identical).
 */
@Immutable
data class RenderableTurn(
    val renderItems: List<RenderItem>,
    val isEmpty: Boolean,
    val errorText: String?,
    val agentName: String?,
    val modelId: String?,
    val durationMs: Long?,
    val stepFinishes: List<Part.StepFinish>,
    val taskAgentName: String?,
    val copyText: String?,
)

@Immutable
sealed class RenderItem {
    @Immutable
    data class TurnDivider(val msgId: String) : RenderItem()
    @Immutable
    data class GroupedParts(val group: PartGroup) : RenderItem()
}

/**
 * Compute all rendering data for a display item in a single pass.
 * Called from a `remember` block — runs only when messages change, not during composition.
 */
fun computeRenderableTurn(
    turnMessages: List<ChatMessage>?,
    currentMessage: ChatMessage,
    isTurnLast: Boolean,
    formatError: (dev.leonardo.ocbeacon.domain.model.Message.Assistant.ErrorInfo?) -> String?,
): RenderableTurn {
    val ordered = turnMessages?.reversed() ?: listOf(currentMessage)

    // Single pass: filter + group + dividers
    val renderItems = mutableListOf<RenderItem>()
    for ((msgIndex, msg) in ordered.withIndex()) {
        val msgParts = filterRenderableParts(msg.parts)
        val groups = groupContextParts(msgParts)
        for (group in groups) {
            renderItems.add(RenderItem.GroupedParts(group))
        }
        if (msgIndex < ordered.lastIndex && msgParts.isNotEmpty()) {
            renderItems.add(RenderItem.TurnDivider(msg.message.id))
        }
    }

    // Error text — first error in turn
    val errorText = ordered.firstNotNullOfOrNull { msg ->
        val am = msg.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant
        formatError(am?.error)
    }

    // Agent name and model from the current message — always extract regardless
    // of isTurnLast, because the displayItems filter may select a non-last
    // assistant as the turn representative.
    val currentAssistant = currentMessage.message as? dev.leonardo.ocbeacon.domain.model.Message.Assistant
    val agentName = currentAssistant?.agent
    val modelId = currentAssistant?.modelId

    // Duration — from current message's own time, not turn span
    val durationMs: Long? = currentAssistant?.let { am ->
        am.time.completed?.let { end -> end - am.time.created }
    }

    // Step finishes for token stats
    val stepFinishes = if (isTurnLast) {
        ordered.flatMap { msg -> msg.parts.filterIsInstance<Part.StepFinish>() }
    } else {
        emptyList()
    }

    // Task agent name (for task tool parts)
    val taskAgentName = ordered
        .flatMap { it.parts }
        .filterIsInstance<Part.Agent>()
        .firstOrNull()?.name?.takeIf { it.isNotBlank() }

    // Copy text — all text parts joined
    val copyText = ordered
        .flatMap { it.parts.filterIsInstance<Part.Text>() }
        .map { it.text }
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

    return RenderableTurn(
        renderItems = renderItems,
        isEmpty = renderItems.isEmpty() && errorText == null,
        errorText = errorText,
        agentName = agentName,
        modelId = modelId,
        durationMs = durationMs,
        stepFinishes = stepFinishes,
        taskAgentName = taskAgentName,
        copyText = copyText,
    )
}
