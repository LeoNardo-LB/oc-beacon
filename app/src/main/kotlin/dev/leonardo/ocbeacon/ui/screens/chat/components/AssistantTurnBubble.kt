package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.filterRenderableParts
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatAssistantErrorMessage
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

@Composable
internal fun AssistantTurnBubble(
    messages: List<ChatMessage>,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleBorder = if (isAmoled) {
        BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
        )
    } else {
        null
    }

    // Collect all renderable content from all messages in the turn
    // Keep parts in their original order so tool calls are interleaved with text/reasoning
    val allContent = remember(messages) {
        messages.mapNotNull { msg ->
            val parts = msg.parts
            val assistantMsg = msg.message as? Message.Assistant ?: return@mapNotNull null
            val errorText = formatAssistantErrorMessage(assistantMsg.error)

            val renderableParts = filterRenderableParts(parts)

            if (renderableParts.isEmpty() && errorText == null) {
                null
            } else {
                Pair(renderableParts, errorText to assistantMsg)
            }
        }
    }

    // Extract agent names from Part.Agent in each message's full parts list
    // Map: part.id -> agentName (for task tools to look up sibling agent names)
    val taskAgentNames = remember(messages) {
        val result = mutableMapOf<String, String?>()
        for (msg in messages) {
            val agentParts = msg.parts.filterIsInstance<Part.Agent>()
            val agentName = agentParts.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            // Find all task tool parts in this message and associate with agentName
            msg.parts.filterIsInstance<Part.Tool>().filter { it.tool == "task" }.forEach { taskPart ->
                result[taskPart.id] = agentName
            }
        }
        result
    }

    if (allContent.isEmpty()) return

    // Use the first message's assistant info for the header
    val firstAssistant = messages.firstOrNull()?.message as? Message.Assistant

    val compact = LocalChatDensity.current == ChatDensity.Compact
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = backgroundColor,
            border = bubbleBorder,
            tonalElevation = if (isAmoled) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 16.dp,
                    vertical = if (compact) 8.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
            ) {
                // "Response" header with provider icon and copy button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (firstAssistant?.providerId != null) {
                            ProviderIcon(
                                providerId = firstAssistant.providerId,
                                size = 12.dp,
                                tint = textColor.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                        Text(
                            text = stringResource(R.string.chat_response),
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.8.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                    if (onCopyText != null) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy),
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { performHaptic(hapticView, hapticOn); onCopyText() },
                            tint = textColor.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                }

                // Render all messages' parts in original order (text, tool, reasoning interleaved)
                for ((renderableParts, errorPair) in allContent) {
                    val (errorText, assistantMsg) = errorPair

                    for (part in renderableParts) {
                        key(part.id) {
                            PartContent(
                                part = part,
                                textColor = textColor,
                                isUser = false,
                                onViewSubSession = onViewSubSession,
                                turnAgentName = if (part is Part.Tool && part.tool == "task") taskAgentNames[part.id] else null
                            )
                        }
                    }

                    // Error display
                    if (errorText != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
                            shape = ShapeTokens.mediumSmall,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) AlphaTokens.HIGH else AlphaTokens.FAINT)),
                            tonalElevation = 0.dp,
                        ) {
                            ErrorPayloadContent(
                                text = errorText,
                                textStyle = MaterialTheme.typography.bodySmall,
                                textColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
