package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.components.AmoledCard
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.screens.chat.components.QuestionPagerView
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Interactive card for answering agent questions.
 * Supports single/multi-select options and "Type your own answer" expands an inline text field.
 */
@Composable
internal fun QuestionCard(
    question: SseEvent.QuestionAsked,
    onSubmit: (answers: List<List<String>>) -> Unit,
    onReject: () -> Unit,
    positionLabel: String? = null,
    initiallySubmitted: Boolean = false,
    initialAnswers: List<List<String>> = emptyList()
) {
    val isAmoled = isAmoledTheme()
    val isSingle = question.questions.size == 1 && question.questions[0].multiple != true

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // Prevent multiple submissions — state is scoped per question via remember(key)
    var submitted by remember(question.id) { mutableStateOf(initiallySubmitted) }
    // Collapsed by default — tap header to expand options.
    // For history (initiallySubmitted), start expanded so user sees answers immediately.
    var expanded by remember(question.id) { mutableStateOf(true) }  // always expanded — no collapse

    // Track answers per question
    val answersPerQuestion = remember {
        mutableStateListOf<List<String>>().apply {
            if (initiallySubmitted && initialAnswers.isNotEmpty()) {
                repeat(question.questions.size) { idx ->
                    add(if (idx < initialAnswers.size) initialAnswers[idx] else emptyList())
                }
            } else {
                repeat(question.questions.size) { add(emptyList()) }
            }
        }
    }

    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    AmoledCard(
        isAmoledDark = isAmoled,
        normalContainerColor = containerColor,
        shape = ShapeTokens.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            // Header row — clickable to expand/collapse, shows question summary
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeTokens.small)
                    .clickable {
                        performHaptic(hapticView, hapticOn)
                        expanded = !expanded
                    }
            ) {
                Icon(
                    @Suppress("DEPRECATION")
                    Icons.Default.HelpOutline,
                    contentDescription = stringResource(R.string.a11y_icon_question),
                    modifier = Modifier.size(18.dp),
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.chat_question_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                // Show first question text as summary (truncated)
                val summary = question.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = AlphaTokens.FAINT)
                )
            }

            // Expandable content — tap header to toggle
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
            // Sub-agent source label (shown when question comes from a child session)
            if (question.sourceSessionTitle != null) {
                Text(
                    text = question.sourceSessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTokens.MEDIUM)
                )
            }

            // Question sections
            QuestionPagerView(
                questions = question.questions,
                selectedAnswers = answersPerQuestion.map { it.toSet() },
                readOnly = submitted,
                onOptionClick = { pageIndex, label ->
                    if (!submitted) {
                        performHaptic(hapticView, hapticOn)
                        if (isSingle) {
                            submitted = true
                            onSubmit(listOf(listOf(label)))
                        } else {
                            val current = answersPerQuestion.getOrNull(pageIndex)?.toMutableList() ?: mutableListOf()
                            if (label in current) current.remove(label) else current.add(label)
                            if (pageIndex < answersPerQuestion.size) answersPerQuestion[pageIndex] = current
                        }
                    }
                }
            )

                // Bottom actions — hidden in history mode (initiallySubmitted)
                if (!initiallySubmitted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { if (!submitted) { performHaptic(hapticView, hapticOn); submitted = true; onReject() } },
                            enabled = !submitted
                        ) {
                            Text(stringResource(R.string.chat_dismiss))
                        }
                        if (!isSingle) {
                            Button(
                                onClick = {
                                    if (!submitted && answersPerQuestion.any { it.isNotEmpty() }) {
                                        performHaptic(hapticView, hapticOn)
                                        submitted = true
                                        onSubmit(answersPerQuestion.map { it.toList() })
                                    }
                                },
                                enabled = !submitted && answersPerQuestion.any { it.isNotEmpty() }
                            ) {
                                Text(stringResource(R.string.question_submit))
                            }
                        }
                    }
                }
                } // close inner Column
            } // close AnimatedVisibility
        }
    }
}
