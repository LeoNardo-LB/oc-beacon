package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ContextToolGroupCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalShowTurnDividers
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MessageCardAssistant(
    renderableTurn: RenderableTurn,
    currentMessage: ChatMessage,
    onViewSubSession: ((String) -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
    isAmoled: Boolean,
    isTurnLast: Boolean,
    agents: List<AgentInfo> = emptyList(),
    onCopy: (() -> Unit)? = null,
) {
    val textColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

    if (renderableTurn.isEmpty) return

    val compact = LocalChatDensity.current == ChatDensity.Compact
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val showTurnDividers = LocalShowTurnDividers.current

    // Keep for footer display (time, provider icon)
    val assistantMsg = currentMessage.message as? Message.Assistant
    val isStreaming = assistantMsg?.time?.completed == null

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = if (isAmoled) AmoledDefaultBorder else null,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else SpacingTokens.LG.dp,
                    vertical = if (compact) SpacingTokens.SM.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else SpacingTokens.XS.dp)
            ) {
                // Render pre-computed items — zero filtering/grouping during composition.
                for (item in renderableTurn.renderItems) {
                    when (item) {
                        is RenderItem.TurnDivider -> {
                            if (showTurnDividers) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = if (compact) 3.dp else 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        is RenderItem.GroupedParts -> {
                            when (item.group) {
                                is PartGroup.Context -> key(item.group.parts.first().id) {
                                    ContextToolGroupCard(
                                        parts = item.group.parts,
                                        onOpenFile = onOpenFile ?: {},
                                    )
                                }
                                is PartGroup.Single -> key(item.group.part.id) {
                                    PartContent(
                                        part = item.group.part,
                                        textColor = textColor,
                                        isUser = false,
                                        onViewSubSession = onViewSubSession,
                                        onOpenFile = onOpenFile,
                                        turnAgentName = if (item.group.part is Part.Tool && item.group.part.tool == "task") {
                                            renderableTurn.taskAgentName
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                }

                // Pre-computed metadata
                val agentName = renderableTurn.agentName
                val stepFinishes = renderableTurn.stepFinishes
                val copyText = renderableTurn.copyText

                // Streaming footer — minimal: time + agent + spinning circle
                // Only shows when no stepFinishes yet. Once StepFinish arrives, the
                // token/cost footer below replaces it. Without this guard, both footers
                // render simultaneously during the gap between StepFinish arrival and
                // message completion (time.completed set), causing a duplicate stats bar.
                if (isStreaming && isTurnLast && stepFinishes.isEmpty()) {
                    Spacer(modifier = Modifier.height(if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        assistantMsg?.time?.created?.let { createdMs ->
                            val timeText = remember(createdMs) {
                                SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(Date(createdMs))
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                        if (!agentName.isNullOrBlank()) {
                            val tagColor = agentColor(agentName, agents)
                            Surface(
                                shape = ShapeTokens.smallMedium,
                                color = tagColor.copy(alpha = AlphaTokens.FAINT)
                            ) {
                                Text(
                                    text = agentName.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = tagColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }

                // Token/cost/duration footer — show for any completed assistant message.
                // No longer gated on stepFinishes or isTurnLast — each displayed
                // assistant message has its own metadata from Message.Assistant fields.
                if (!isStreaming) {
                    val durationMs = renderableTurn.durationMs
                    val modelId = renderableTurn.modelId

                    val hasFooter = (durationMs ?: 0) > 0 || !modelId.isNullOrBlank() || !agentName.isNullOrBlank()

                    if (hasFooter) {
                        Spacer(modifier = Modifier.height(if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 时间
                            assistantMsg?.time?.created?.let { createdMs ->
                                val timeText = remember(createdMs) {
                                    SimpleDateFormat("HH:mm", Locale.getDefault())
                                        .format(Date(createdMs))
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                            // Agent name tag (styled like QUEUED badge with agent color)
                            if (!agentName.isNullOrBlank()) {
                                val tagColor = agentColor(agentName, agents)
                                Surface(
                                    shape = ShapeTokens.smallMedium,
                                    color = tagColor.copy(alpha = AlphaTokens.FAINT)
                                ) {
                                    Text(
                                        text = agentName.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = tagColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            // Provider icon + model name (tight 3dp spacing, matching input bar)
                            val hasProviderOrModel = assistantMsg?.providerId != null || !modelId.isNullOrBlank()
                            if (hasProviderOrModel) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    if (assistantMsg?.providerId != null) {
                                        ProviderIcon(
                                            providerId = assistantMsg.providerId,
                                            size = 10.dp,
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                        )
                                    }
                                    if (!modelId.isNullOrBlank()) {
                                        Text(
                                            text = modelId,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (durationMs != null && durationMs > 0) {
                                Text(
                                    text = formatDuration(durationMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (copyText != null) {
                                CopyButton(
                                    text = copyText,
                                    modifier = Modifier.size(14.dp),
                                    onCopied = onCopy
                                )
                            }
                        }
                    }
                    // Fallback: no stats but copy button needed
                    if (!hasFooter && isTurnLast && copyText != null) {
                        Spacer(modifier = Modifier.height(if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 时间
                            assistantMsg?.time?.created?.let { createdMs ->
                                val timeText = remember(createdMs) {
                                    SimpleDateFormat("HH:mm", Locale.getDefault())
                                        .format(Date(createdMs))
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                            // Agent name tag
                            if (!agentName.isNullOrBlank()) {
                                val tagColor = agentColor(agentName, agents)
                                Surface(
                                    shape = ShapeTokens.smallMedium,
                                    color = tagColor.copy(alpha = AlphaTokens.FAINT)
                                ) {
                                    Text(
                                        text = agentName.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = tagColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            CopyButton(
                                text = copyText,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }


                }

                // Fallback footer: no StepFinish but still show Agent Tag + time
                if (stepFinishes.isEmpty() && !isStreaming && isTurnLast && !agentName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 时间
                        assistantMsg?.time?.created?.let { createdMs ->
                            val timeText = remember(createdMs) {
                                SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(Date(createdMs))
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                        // Agent name tag
                        val tagColor = agentColor(agentName, agents)
                        Surface(
                            shape = ShapeTokens.smallMedium,
                            color = tagColor.copy(alpha = AlphaTokens.FAINT)
                        ) {
                            Text(
                                text = agentName.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = tagColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (copyText != null) {
                            CopyButton(
                                text = copyText,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Error display
                if (renderableTurn.errorText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
                        shape = ShapeTokens.mediumSmall,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) AlphaTokens.HIGH else AlphaTokens.FAINT)),
                        tonalElevation = 0.dp,
                    ) {
                        ErrorPayloadContent(
                            text = renderableTurn.errorText,
                            textStyle = MaterialTheme.typography.bodySmall,
                            textColor = textColor,
                            modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}
